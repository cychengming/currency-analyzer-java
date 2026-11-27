package com.currencyanalyzer.quant.pipeline;

import org.hipparchus.stat.descriptive.DescriptiveStatistics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 5 portfolio ledger: marks positions to market in USD, decomposes Spot PnL vs.
 * Carry PnL vs. Friction Drag, and computes risk-adjusted portfolio metrics.
 */
public final class PortfolioLedger {

    private static final double TRADING_DAYS_PER_YEAR = 252.0;

    private PortfolioLedger() {
    }

    public static double resolveQuoteToUsd(
        InstrumentSpec spec,
        double pairPrice,
        Map<String, FxDailyBar> barsByPair
    ) {
        return resolveQuoteToUsd(spec, pairPrice, barsByPair, true);
    }

    public static double resolveQuoteToUsdAtOpen(
        InstrumentSpec spec,
        double pairOpenPrice,
        Map<String, FxDailyBar> barsByPair
    ) {
        return resolveQuoteToUsd(spec, pairOpenPrice, barsByPair, false);
    }

    private static double resolveQuoteToUsd(
        InstrumentSpec spec,
        double pairPrice,
        Map<String, FxDailyBar> barsByPair,
        boolean useClose
    ) {
        String quote = spec.quoteCurrency();
        if ("USD".equals(quote)) {
            return 1.0;
        }
        if ("USD".equals(spec.baseCurrency()) && pairPrice > 0) {
            return 1.0 / pairPrice;
        }
        if (barsByPair != null) {
            FxDailyBar direct = barsByPair.get(quote + "/USD");
            if (direct != null) {
                double p = useClose ? direct.close() : direct.open();
                if (p > 0) {
                    return p;
                }
            }
            FxDailyBar inverse = barsByPair.get("USD/" + quote);
            if (inverse != null) {
                double p = useClose ? inverse.close() : inverse.open();
                if (p > 0) {
                    return 1.0 / p;
                }
            }
        }
        return pairPrice > 0 ? 1.0 / pairPrice : 1.0;
    }

    public static double computePairSpotPnlUsd(
        double priorUnits,
        double deltaUnits,
        FxDailyBar priorBar,
        FxDailyBar execBar,
        double quoteToUsdAtClose
    ) {
        double existingQuotePnl = priorBar == null
            ? 0.0
            : priorUnits * (execBar.close() - priorBar.close());
        double deltaQuotePnl = deltaUnits * (execBar.close() - execBar.open());
        return (existingQuotePnl + deltaQuotePnl) * quoteToUsdAtClose;
    }

    public static Map<String, Double> computeNetCurrencyExposureUsd(Map<String, OpenPosition> positions) {
        Map<String, Double> exposure = new LinkedHashMap<>();
        if (positions == null) {
            return Map.of();
        }
        for (OpenPosition pos : positions.values()) {
            if (Math.abs(pos.currentNotionalUsd()) < 1e-6) {
                continue;
            }
            InstrumentSpec spec = InstrumentCatalog.forPair(pos.pair());
            exposure.merge(spec.baseCurrency(), pos.currentNotionalUsd(), Double::sum);
            exposure.merge(spec.quoteCurrency(), -pos.currentNotionalUsd(), Double::sum);
        }
        return Map.copyOf(exposure);
    }

    public static PipelineSummary summarize(
        double initialEquityUsd,
        List<LedgerSnapshot> curve,
        int executedTradesCount,
        int inertiaSkippedCount,
        double totalTradedNotionalUsd
    ) {
        if (curve == null || curve.isEmpty()) {
            return new PipelineSummary(
                initialEquityUsd, initialEquityUsd, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                executedTradesCount, inertiaSkippedCount, 0.0
            );
        }

        LedgerSnapshot last = curve.getLast();
        double finalEquity = last.equityUsd();
        double totalReturnPct = (finalEquity - initialEquityUsd) / initialEquityUsd * 100.0;
        double years = Math.max(1.0 / TRADING_DAYS_PER_YEAR, curve.size() / TRADING_DAYS_PER_YEAR);
        double cagrPct = finalEquity > 0
            ? (Math.pow(finalEquity / initialEquityUsd, 1.0 / years) - 1.0) * 100.0
            : -100.0;

        double[] netReturns = new double[curve.size()];
        double[] spotReturns = new double[curve.size()];
        List<Double> downsideReturns = new ArrayList<>();
        double prevEquity = initialEquityUsd;
        double maxDrawdownPct = 0.0;

        for (int i = 0; i < curve.size(); i++) {
            LedgerSnapshot snap = curve.get(i);
            double denom = prevEquity > 0 ? prevEquity : initialEquityUsd;
            double netRet = snap.netDailyPnlUsd() / denom;
            double spotRet = snap.dailySpotPnlUsd() / denom;
            netReturns[i] = netRet;
            spotReturns[i] = spotRet;
            if (netRet < 0) {
                downsideReturns.add(netRet);
            }
            maxDrawdownPct = Math.min(maxDrawdownPct, snap.drawdownPct());
            prevEquity = snap.equityUsd();
        }

        DescriptiveStatistics netStats = new DescriptiveStatistics(netReturns);
        DescriptiveStatistics spotStats = new DescriptiveStatistics(spotReturns);

        double netDailyStd = netStats.getN() >= 2 ? netStats.getStandardDeviation() : 0.0;
        double annualizedVolPct = netDailyStd * Math.sqrt(TRADING_DAYS_PER_YEAR) * 100.0;
        double netSharpe = netDailyStd > 1e-12
            ? (netStats.getMean() / netDailyStd) * Math.sqrt(TRADING_DAYS_PER_YEAR)
            : 0.0;

        double spotDailyStd = spotStats.getN() >= 2 ? spotStats.getStandardDeviation() : 0.0;
        double spotSharpe = spotDailyStd > 1e-12
            ? (spotStats.getMean() / spotDailyStd) * Math.sqrt(TRADING_DAYS_PER_YEAR)
            : 0.0;

        double sortino = 0.0;
        if (downsideReturns.size() >= 2) {
            DescriptiveStatistics downStats = new DescriptiveStatistics();
            downsideReturns.forEach(downStats::addValue);
            double downStd = downStats.getStandardDeviation();
            if (downStd > 1e-12) {
                sortino = (netStats.getMean() / downStd) * Math.sqrt(TRADING_DAYS_PER_YEAR);
            }
        }

        double calmar = Math.abs(maxDrawdownPct) > 1e-9
            ? cagrPct / Math.abs(maxDrawdownPct)
            : 0.0;

        double[] sortedNet = netReturns.clone();
        Arrays.sort(sortedNet);
        int varIdx = Math.max(0, (int) Math.floor(0.05 * (sortedNet.length - 1)));
        double historicalVar95DailyPct = sortedNet[varIdx] * 100.0;

        double avgEquity = curve.stream().mapToDouble(LedgerSnapshot::equityUsd).average().orElse(initialEquityUsd);
        double annualizedTurnover = avgEquity > 0 ? (totalTradedNotionalUsd / avgEquity) / years : 0.0;

        return new PipelineSummary(
            round2(initialEquityUsd),
            round2(finalEquity),
            round2(totalReturnPct),
            round2(cagrPct),
            round2(annualizedVolPct),
            round2(netSharpe),
            round2(spotSharpe),
            round2(sortino),
            round2(calmar),
            round2(maxDrawdownPct),
            round2(historicalVar95DailyPct),
            round2(last.cumulativeSpotPnlUsd()),
            round2(last.cumulativeCarryPnlUsd()),
            round2(last.cumulativeFrictionCostUsd()),
            executedTradesCount,
            inertiaSkippedCount,
            round2(annualizedTurnover)
        );
    }

    public static LocalDate earliestCommonWarmupDate(Map<String, List<FxDailyBar>> barsByPair) {
        return barsByPair.values().stream()
            .filter(list -> !list.isEmpty())
            .map(list -> list.getFirst().date())
            .max(LocalDate::compareTo)
            .orElse(LocalDate.now());
    }

    private static double round2(double value) {
        return Double.isFinite(value) ? Math.round(value * 100.0) / 100.0 : 0.0;
    }
}
