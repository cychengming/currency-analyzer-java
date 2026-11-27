package com.currencyanalyzer.quant.pipeline;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Stage 4 execution & carry simulator:
 * fills delta orders strictly at Day {@code t+1} Open with spread/slippage friction,
 * and accrues daily rollover carry (3x on Wednesdays) net of broker financing markup.
 */
public class ExecutionAndCarrySimulator {

    private static final double DAYS_PER_YEAR = 365.0;

    public FillReport simulateFillAndRollover(
        OrderDelta order,
        FxDailyBar executionBar,
        double quoteToUsdAtOpen,
        double quoteToUsdAtClose,
        PipelineConfig config
    ) {
        InstrumentSpec spec = InstrumentCatalog.forPair(order.pair());
        double midOpen = executionBar.open();
        double oneWaySlippageInPrice = spec.defaultOneWayCostPips() * spec.pipSize();
        double delta = order.deltaBaseUnits();

        double fillPrice = midOpen;
        double frictionCostUsd = 0.0;
        if (Math.abs(delta) > 1e-9) {
            fillPrice = delta > 0 ? midOpen + oneWaySlippageInPrice : midOpen - oneWaySlippageInPrice;
            frictionCostUsd = Math.abs(delta) * oneWaySlippageInPrice * quoteToUsdAtOpen;
        }

        double postFillUnits = order.targetBaseUnits();
        double signedNotionalUsd = postFillUnits * executionBar.close() * quoteToUsdAtClose;
        int rolloverDays = rolloverDaysFor(executionBar.date());
        double dailyCarryUsd = 0.0;

        if (config.enableCarry() && rolloverDays > 0 && Math.abs(signedNotionalUsd) > 1e-6) {
            double rateDiffDecimal = (executionBar.basePolicyRatePct() - executionBar.quotePolicyRatePct()) / 100.0;
            double markupCostAnnual = Math.abs(signedNotionalUsd) * config.brokerCarryMarkupAnnual();
            double grossAnnualCarry = signedNotionalUsd * rateDiffDecimal;
            dailyCarryUsd = (grossAnnualCarry - markupCostAnnual) * (rolloverDays / DAYS_PER_YEAR);
        }

        return new FillReport(
            order.pair(),
            executionBar.date(),
            delta,
            fillPrice,
            midOpen,
            frictionCostUsd,
            dailyCarryUsd,
            rolloverDays
        );
    }

    static int rolloverDaysFor(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.WEDNESDAY) {
            return 3;
        }
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return 0;
        }
        return 1;
    }
}
