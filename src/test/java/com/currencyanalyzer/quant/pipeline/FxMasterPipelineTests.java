package com.currencyanalyzer.quant.pipeline;

import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FxMasterPipelineTests {

    @Test
    void referenceTraceMathMatchesVolatilitySizingCarryRolloverAndQuoteUsdConversion() {
        VolatilityTargetRiskSizer sizer = new VolatilityTargetRiskSizer();
        ExecutionAndCarrySimulator simulator = new ExecutionAndCarrySimulator();

        LocalDate tuesday = LocalDate.of(2024, 11, 19); // Tuesday
        LocalDate wednesday = LocalDate.of(2024, 11, 20); // Wednesday -> 3x rollover
        assertThat(wednesday.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);

        FxDailyBar bar = new FxDailyBar("USD/JPY", tuesday, 152.20, 152.80, 152.00, 152.40, 0.0, 4.75, 0.25);
        PortfolioState state = new PortfolioState(tuesday, 100_000.0, 100_000.0, 0.0, Map.of(), Map.of());
        PipelineConfig config = new PipelineConfig(100_000.0, 0.10, 1.50, 0.10, 0.01, true, 100);

        Map<String, TargetPosition> targets = sizer.sizeTargets(
            tuesday,
            Map.of("USD/JPY", 1.0),
            Map.of("USD/JPY", bar),
            Map.of("USD/JPY", 0.115),
            state,
            config,
            new IdentityRegimeOverlay()
        );

        TargetPosition target = targets.get("USD/JPY");
        // $100,000 * 10% / 11.5% = $86,956.52 notional
        assertThat(target.targetNotionalUsd()).isCloseTo(86_956.52, within(0.10));
        assertThat(target.targetBaseUnits()).isCloseTo(86_956.52, within(0.10));

        // 1x day rollover at $86,950 notional -> (86950 * (4.50% - 1.00%)) / 365 = +$8.34/day
        FxDailyBar tuesdayExec = new FxDailyBar("USD/JPY", tuesday, 152.40, 152.40, 152.40, 152.40, 0.0, 4.75, 0.25);
        FillReport oneDayFill = simulator.simulateFillAndRollover(
            new OrderDelta("USD/JPY", tuesday.minusDays(1), 0.0, 86_950.0, 86_950.0),
            tuesdayExec,
            1.0 / 152.40,
            1.0 / 152.40,
            config
        );
        assertThat(oneDayFill.rolloverDays()).isEqualTo(1);
        assertThat(oneDayFill.dailyCarryUsd()).isCloseTo(8.34, within(0.02));
        // 2 pips * 0.01 = 0.02 JPY per USD base unit -> 86950 * 0.02 / 152.40 = ~$11.41
        assertThat(oneDayFill.frictionCostUsd()).isCloseTo(11.41, within(0.05));

        // Wednesday 3x swap -> ~3 * 8.34 = $25.01
        FxDailyBar wedExec = new FxDailyBar("USD/JPY", wednesday, 152.40, 152.40, 152.40, 152.40, 0.0, 4.75, 0.25);
        FillReport wedFill = simulator.simulateFillAndRollover(
            new OrderDelta("USD/JPY", tuesday, 86_950.0, 86_950.0, 0.0),
            wedExec,
            1.0 / 152.40,
            1.0 / 152.40,
            config
        );
        assertThat(wedFill.rolloverDays()).isEqualTo(3);
        assertThat(wedFill.dailyCarryUsd()).isCloseTo(25.01, within(0.05));
        assertThat(wedFill.frictionCostUsd()).isZero();

        // Short USD/JPY pays negative carry + broker markup (never receives markup!)
        FillReport shortFill = simulator.simulateFillAndRollover(
            new OrderDelta("USD/JPY", tuesday.minusDays(1), 0.0, -86_950.0, -86_950.0),
            tuesdayExec,
            1.0 / 152.40,
            1.0 / 152.40,
            config
        );
        // -86950 * 0.045 - 86950 * 0.01 = -4782.25 / 365 = -$13.10/day
        assertThat(shortFill.dailyCarryUsd()).isCloseTo(-13.10, within(0.05));
    }

    @Test
    void enforcesWarmupGuardNextDayOpenExecutionInertiaBufferAndClusterCaps() {
        MasterPipelineConfiguration configFactory = new MasterPipelineConfiguration();
        FxMasterPipelineRunner runner = configFactory.fxMasterPipelineRunner(
            configFactory.volatilityTargetRiskSizer(),
            configFactory.executionAndCarrySimulator()
        );

        List<FxDailyBar> trendBars = trendingSeries("EUR/USD", LocalDate.of(2022, 1, 3), 320, 1.1000, 0.0005, 4.5, 2.0);
        BarSeries series = FxMasterPipelineRunner.toBarSeries("EUR/USD", trendBars);
        CarverAqrSignalEngine carver = new CarverAqrSignalEngine();

        // Strict 252-bar warm-up prevents premature trades
        assertThat(carver.computeSignal(series, trendBars, 0)).isZero();
        assertThat(carver.computeSignal(series, trendBars, 251)).isZero();
        assertThat(carver.computeSignal(series, trendBars, 260)).isPositive();

        // Anti-leakage: Day t+1 Open fill price must be independent of Day t+1 Close
        List<FxDailyBar> mutatedCloseBars = new ArrayList<>(trendBars);
        int lastIdx = mutatedCloseBars.size() - 1;
        FxDailyBar origLast = mutatedCloseBars.get(lastIdx);
        mutatedCloseBars.set(lastIdx, new FxDailyBar(
            origLast.pair(), origLast.date(), origLast.open(), origLast.high() * 1.10, origLast.low() * 0.90,
            origLast.close() * 1.08, origLast.volume(), origLast.basePolicyRatePct(), origLast.quotePolicyRatePct()
        ));

        PipelineConfig cfg = PipelineConfig.defaults();
        PipelineBacktestResult baseRun = runner.run("base", Map.of("EUR/USD", trendBars), carver, new IdentityRegimeOverlay(), cfg);
        PipelineBacktestResult mutatedRun = runner.run("mutated", Map.of("EUR/USD", mutatedCloseBars), carver, new IdentityRegimeOverlay(), cfg);

        assertThat(mutatedRun.traces().getLast().fill().fillPrice())
            .isEqualTo(baseRun.traces().getLast().fill().fillPrice());

        // Inertia buffer reduces executed trade count vs zero inertia in choppy markets
        List<FxDailyBar> chopBars = choppySeries("EUR/USD", LocalDate.of(2016, 1, 4), 420, 1.1200, 0.008);
        PipelineBacktestResult noInertia = runner.run(
            "no-inertia", Map.of("EUR/USD", chopBars), carver, new IdentityRegimeOverlay(),
            new PipelineConfig(100_000.0, 0.10, 1.50, 0.0, 0.01, true, 50)
        );
        PipelineBacktestResult withInertia = runner.run(
            "with-inertia", Map.of("EUR/USD", chopBars), carver, new IdentityRegimeOverlay(), cfg
        );
        assertThat(withInertia.summary().inertiaSkippedCount()).isGreaterThan(0);
        assertThat(withInertia.summary().executedTradesCount()).isLessThan(noInertia.summary().executedTradesCount());
        assertThat(withInertia.summary().totalFrictionCostUsd()).isLessThanOrEqualTo(noInertia.summary().totalFrictionCostUsd());

        // Currency cluster exposure cap bounds correlated short-USD bets across EUR/USD, GBP/USD, GOLD/USD
        VolatilityTargetRiskSizer sizer = new VolatilityTargetRiskSizer();
        LocalDate d = LocalDate.of(2024, 5, 1);
        Map<String, FxDailyBar> multiBars = Map.of(
            "EUR/USD", new FxDailyBar("EUR/USD", d, 1.08, 1.085, 1.075, 1.08, 0, 4.0, 5.5),
            "GBP/USD", new FxDailyBar("GBP/USD", d, 1.25, 1.255, 1.245, 1.25, 0, 5.25, 5.5),
            "GOLD/USD", new FxDailyBar("GOLD/USD", d, 2300.0, 2310.0, 2290.0, 2300.0, 0, 0.0, 5.5)
        );
        Map<String, TargetPosition> capped = sizer.sizeTargets(
            d,
            Map.of("EUR/USD", 1.0, "GBP/USD", 1.0, "GOLD/USD", 1.0),
            multiBars,
            Map.of("EUR/USD", 0.03, "GBP/USD", 0.03, "GOLD/USD", 0.03),
            new PortfolioState(d, 100_000.0, 100_000.0, 0.0, Map.of(), Map.of()),
            new PipelineConfig(100_000.0, 0.15, 1.50, 0.10, 0.01, true, 10),
            new IdentityRegimeOverlay()
        );
        assertThat(capped.values()).allMatch(TargetPosition::cappedByExposureLimit);
        double totalUsdExposure = capped.values().stream().mapToDouble(TargetPosition::targetNotionalUsd).sum();
        assertThat(totalUsdExposure).isLessThanOrEqualTo(150_001.0);
    }

    @Test
    void masterArenaComparesCarverDruckenmillerDonchianAndDrawdownOverlayAcrossRegimes() {
        MasterPipelineConfiguration factory = new MasterPipelineConfiguration();
        FxMasterPipelineRunner runner = factory.fxMasterPipelineRunner(
            factory.volatilityTargetRiskSizer(),
            factory.executionAndCarrySimulator()
        );
        Map<String, MasterStrategyProfile> profiles = factory.masterStrategyProfiles();
        assertThat(profiles).containsKeys(
            CarverAqrSignalEngine.ENGINE_ID,
            "carver-guarded-overlay",
            DonchianBreakoutSignalEngine.ENGINE_ID,
            DruckenmillerRateImpulseSignalEngine.ENGINE_ID
        );

        // Regime 1: Sustained hiking trend + positive carry -> positive Spot & positive Carry attribution
        List<FxDailyBar> usdJpyTrend = trendingSeries("USD/JPY", LocalDate.of(2022, 1, 3), 420, 115.0, 0.10, 5.25, 0.10);
        List<FxDailyBar> goldTrend = trendingSeries("GOLD/USD", LocalDate.of(2022, 1, 3), 420, 1850.0, 1.40, 0.0, 5.25);

        PipelineBacktestResult carverResult = runner.run(
            CarverAqrSignalEngine.ENGINE_ID,
            Map.of("USD/JPY", usdJpyTrend, "GOLD/USD", goldTrend),
            profiles.get(CarverAqrSignalEngine.ENGINE_ID).signalEngine(),
            profiles.get(CarverAqrSignalEngine.ENGINE_ID).riskOverlay(),
            PipelineConfig.defaults()
        );
        assertThat(carverResult.summary().totalSpotPnlUsd()).isPositive();
        assertThat(carverResult.summary().totalCarryPnlUsd()).isPositive();
        assertThat(carverResult.summary().netSharpeRatio()).isPositive();

        PipelineBacktestResult druckenResult = runner.run(
            DruckenmillerRateImpulseSignalEngine.ENGINE_ID,
            Map.of("USD/JPY", usdJpyTrend, "GOLD/USD", goldTrend),
            profiles.get(DruckenmillerRateImpulseSignalEngine.ENGINE_ID).signalEngine(),
            profiles.get(DruckenmillerRateImpulseSignalEngine.ENGINE_ID).riskOverlay(),
            PipelineConfig.defaults()
        );
        PipelineBacktestResult donchianResult = runner.run(
            DonchianBreakoutSignalEngine.ENGINE_ID,
            Map.of("USD/JPY", usdJpyTrend, "GOLD/USD", goldTrend),
            profiles.get(DonchianBreakoutSignalEngine.ENGINE_ID).signalEngine(),
            profiles.get(DonchianBreakoutSignalEngine.ENGINE_ID).riskOverlay(),
            PipelineConfig.defaults()
        );
        assertThat(druckenResult.summary().finalEquityUsd()).isPositive();
        assertThat(donchianResult.summary().finalEquityUsd()).isPositive();

        // Regime 2: Carry unwind crash -> DrawdownAndVolSpikeOverlay limits drawdown vs unthrottled Carver spine
        List<FxDailyBar> unwindBars = carryUnwindSeries("USD/JPY", LocalDate.of(2023, 6, 1), 360);
        PipelineBacktestResult unguarded = runner.run(
            CarverAqrSignalEngine.ENGINE_ID,
            Map.of("USD/JPY", unwindBars),
            profiles.get(CarverAqrSignalEngine.ENGINE_ID).signalEngine(),
            new IdentityRegimeOverlay(),
            new PipelineConfig(100_000.0, 0.15, 1.50, 0.10, 0.01, true, 50)
        );
        PipelineBacktestResult guarded = runner.run(
            "carver-guarded-overlay",
            Map.of("USD/JPY", unwindBars),
            profiles.get("carver-guarded-overlay").signalEngine(),
            new DrawdownAndVolSpikeOverlay(4.0, 12.0, 0.12, 0.40),
            new PipelineConfig(100_000.0, 0.15, 1.50, 0.10, 0.01, true, 50)
        );
        assertThat(Math.abs(guarded.summary().maxDrawdownPct()))
            .isLessThanOrEqualTo(Math.abs(unguarded.summary().maxDrawdownPct()));
    }

    @Test
    void validatesInstrumentCatalogAndPipelineConfigInputs() {
        assertThat(InstrumentCatalog.forPair("GOLD/USD").baseCurrency()).isEqualTo("XAU");
        assertThat(InstrumentCatalog.forPair("XAU/USD").pipSize()).isEqualTo(0.10);
        assertThat(InstrumentCatalog.forPair("SILVER/USD").baseCurrency()).isEqualTo("XAG");
        assertThat(InstrumentCatalog.forPair("XAG/USD").pipSize()).isEqualTo(0.01);

        assertThatThrownBy(() -> InstrumentCatalog.forPair(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstrumentCatalog.forPair("INVALID")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineConfig(-1.0, 0.10, 1.5, 0.1, 0.01, true, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineConfig(100_000.0, 0.0, 1.5, 0.1, 0.01, true, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineConfig(100_000.0, 0.10, 0.0, 0.1, 0.01, true, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineConfig(100_000.0, 0.10, 1.5, 0.9, 0.01, true, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineConfig(100_000.0, 0.10, 1.5, 0.1, -0.01, true, 10)).isInstanceOf(IllegalArgumentException.class);

        // Weekend rollover = 0, carry disabled = 0, cross-pair USD conversions
        ExecutionAndCarrySimulator simulator = new ExecutionAndCarrySimulator();
        LocalDate saturday = LocalDate.of(2024, 11, 23);
        FxDailyBar satBar = new FxDailyBar("EUR/USD", saturday, 1.05, 1.05, 1.05, 1.05, 0.0, 3.5, 4.75);
        FillReport satFill = simulator.simulateFillAndRollover(
            new OrderDelta("EUR/USD", saturday.minusDays(1), 10_000.0, 10_000.0, 0.0),
            satBar, 1.0, 1.0, PipelineConfig.defaults()
        );
        assertThat(satFill.rolloverDays()).isZero();
        assertThat(satFill.dailyCarryUsd()).isZero();

        FillReport carryDisabled = simulator.simulateFillAndRollover(
            new OrderDelta("EUR/USD", saturday.minusDays(2), 10_000.0, 10_000.0, 0.0),
            new FxDailyBar("EUR/USD", saturday.minusDays(1), 1.05, 1.05, 1.05, 1.05, 0.0, 3.5, 4.75),
            1.0, 1.0, new PipelineConfig(100_000.0, 0.10, 1.5, 0.10, 0.01, false, 10)
        );
        assertThat(carryDisabled.dailyCarryUsd()).isZero();

        // Cross-pair quote-to-USD resolution (EUR/JPY via USD/JPY, EUR/GBP via GBP/USD)
        LocalDate d = LocalDate.of(2024, 11, 20);
        Map<String, FxDailyBar> crossBars = Map.of(
            "USD/JPY", new FxDailyBar("USD/JPY", d, 150.0, 151.0, 149.0, 150.0, 0.0, 4.75, 0.25),
            "GBP/USD", new FxDailyBar("GBP/USD", d, 1.25, 1.26, 1.24, 1.25, 0.0, 4.75, 4.75)
        );
        assertThat(PortfolioLedger.resolveQuoteToUsd(InstrumentCatalog.forPair("EUR/JPY"), 160.0, crossBars))
            .isCloseTo(1.0 / 150.0, within(1e-6));
        assertThat(PortfolioLedger.resolveQuoteToUsd(InstrumentCatalog.forPair("EUR/GBP"), 0.85, crossBars))
            .isCloseTo(1.25, within(1e-6));
        assertThat(PortfolioLedger.resolveQuoteToUsdAtOpen(InstrumentCatalog.forPair("EUR/JPY"), 160.0, crossBars))
            .isCloseTo(1.0 / 150.0, within(1e-6));

        // Empty ledger summary, warmup date, null-safe records, overlay, and signal edge branches
        assertThat(PortfolioLedger.summarize(100_000.0, List.of(), 0, 0, 0.0).finalEquityUsd()).isEqualTo(100_000.0);
        assertThat(PortfolioLedger.earliestCommonWarmupDate(Map.of("EUR/USD", List.of(satBar)))).isEqualTo(saturday);
        assertThat(new PortfolioState(d, 100_000.0, 100_000.0, 0.0, null, null).positions()).isEmpty();
        assertThat(new PipelineBacktestResult("p", d, d, PortfolioLedger.summarize(100_000.0, List.of(), 0, 0, 0.0), null, null).equityCurve()).isEmpty();

        DrawdownAndVolSpikeOverlay overlay = new DrawdownAndVolSpikeOverlay();
        assertThat(overlay.id()).isEqualTo(DrawdownAndVolSpikeOverlay.OVERLAY_ID);
        assertThat(overlay.riskMultiplier(d, null, Map.of())).isEqualTo(1.0);
        assertThat(overlay.riskMultiplier(d, new PortfolioState(d, 85_000.0, 100_000.0, -15.0, Map.of(), Map.of()), Map.of("EUR/USD", 0.24, "USD/JPY", 0.20)))
            .isLessThan(0.70);

        // Down-trending series exercises negative Donchian breakout & diverging Druckenmiller fallback
        List<FxDailyBar> downBars = trendingSeries("EUR/USD", LocalDate.of(2023, 1, 2), 280, 1.2000, -0.0005, 2.0, 5.25);
        DonchianBreakoutSignalEngine donchian = new DonchianBreakoutSignalEngine();
        assertThat(donchian.computeSignal(null, downBars, 10)).isZero();
        assertThat(donchian.computeSignal(null, downBars, 270)).isNegative();

        DruckenmillerRateImpulseSignalEngine druck = new DruckenmillerRateImpulseSignalEngine();
        assertThat(druck.computeSignal(null, downBars, 10)).isZero();
        assertThat(druck.computeSignal(null, downBars, 270)).isNegative();

        IdentityRegimeOverlay identity = new IdentityRegimeOverlay();
        assertThat(identity.id()).isEqualTo(IdentityRegimeOverlay.OVERLAY_ID);
    }

    private static boolean isWeekday(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> false;
            default -> true;
        };
    }

    private static List<FxDailyBar> trendingSeries(
        String pair,
        LocalDate start,
        int days,
        double startPrice,
        double dailyDrift,
        double baseRate,
        double quoteRate
    ) {
        List<FxDailyBar> bars = new ArrayList<>(days);
        LocalDate date = start;
        double price = startPrice;
        int added = 0;
        while (added < days) {
            if (isWeekday(date)) {
                double wobble = ((added % 5) - 2) * (startPrice * 0.0015);
                double open = price;
                double close = Math.max(0.01, price + dailyDrift + wobble);
                double high = Math.max(open, close) * 1.003;
                double low = Math.min(open, close) * 0.997;
                bars.add(new FxDailyBar(pair, date, open, high, low, close, 0.0, baseRate, quoteRate));
                price = close;
                added++;
            }
            date = date.plusDays(1);
        }
        return bars;
    }

    private static List<FxDailyBar> choppySeries(
        String pair,
        LocalDate start,
        int days,
        double anchor,
        double amplitude
    ) {
        List<FxDailyBar> bars = new ArrayList<>(days);
        LocalDate date = start;
        int added = 0;
        double prevClose = anchor;
        while (added < days) {
            if (isWeekday(date)) {
                double open = prevClose;
                double close = anchor + Math.sin(added * 0.35) * amplitude;
                double high = Math.max(open, close) * 1.004;
                double low = Math.min(open, close) * 0.996;
                bars.add(new FxDailyBar(pair, date, open, high, low, close, 0.0, 1.0, 0.9));
                prevClose = close;
                added++;
            }
            date = date.plusDays(1);
        }
        return bars;
    }

    private static List<FxDailyBar> carryUnwindSeries(String pair, LocalDate start, int days) {
        List<FxDailyBar> bars = new ArrayList<>(days);
        LocalDate date = start;
        double price = 140.0;
        int added = 0;
        while (added < days) {
            if (isWeekday(date)) {
                double open = price;
                double delta = added < 290 ? 0.08 + ((added % 4) - 1.5) * 0.10 : -0.85;
                double close = Math.max(90.0, price + delta);
                double high = Math.max(open, close) * 1.005;
                double low = Math.min(open, close) * 0.995;
                double jpRate = added < 290 ? 0.0 : 0.25;
                bars.add(new FxDailyBar(pair, date, open, high, low, close, 0.0, 5.25, jpRate));
                price = close;
                added++;
            }
            date = date.plusDays(1);
        }
        return bars;
    }
}
