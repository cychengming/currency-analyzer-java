package com.currencyanalyzer.quant.pipeline;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Orchestrates the 5-stage pure-Java FX & Metals backtesting pipeline with strict
 * point-in-time {@code t} signal -> {@code t+1} Open fill separation and
 * Stage 5 -> Stage 3 equity/exposure feedback.
 */
public class FxMasterPipelineRunner {

    private final VolatilityTargetRiskSizer riskSizer;
    private final ExecutionAndCarrySimulator executionSimulator;

    public FxMasterPipelineRunner(
        VolatilityTargetRiskSizer riskSizer,
        ExecutionAndCarrySimulator executionSimulator
    ) {
        this.riskSizer = riskSizer;
        this.executionSimulator = executionSimulator;
    }

    public PipelineBacktestResult run(
        String profileId,
        Map<String, List<FxDailyBar>> alignedBarsByPair,
        SignalEngine signalEngine,
        RegimeRiskOverlay riskOverlay,
        PipelineConfig config
    ) {
        if (alignedBarsByPair == null || alignedBarsByPair.isEmpty()) {
            throw new IllegalArgumentException("At least one instrument bar series is required");
        }

        Map<String, List<FxDailyBar>> orderedBars = new LinkedHashMap<>();
        Map<String, BarSeries> ta4jSeries = new LinkedHashMap<>();
        Map<String, Map<LocalDate, Integer>> indexByPairAndDate = new LinkedHashMap<>();
        TreeSet<LocalDate> allDates = new TreeSet<>();

        for (Map.Entry<String, List<FxDailyBar>> entry : alignedBarsByPair.entrySet()) {
            List<FxDailyBar> list = entry.getValue();
            if (list == null || list.size() < 2) {
                continue;
            }
            String pair = InstrumentCatalog.forPair(entry.getKey()).pair();
            orderedBars.put(pair, list);
            ta4jSeries.put(pair, toBarSeries(pair, list));
            Map<LocalDate, Integer> dateIdx = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                LocalDate date = list.get(i).date();
                dateIdx.put(date, i);
                allDates.add(date);
            }
            indexByPairAndDate.put(pair, dateIdx);
        }

        if (orderedBars.isEmpty() || allDates.size() < 2) {
            throw new IllegalArgumentException("At least 2 aligned trading days are required");
        }

        List<LocalDate> timeline = new ArrayList<>(allDates);
        LocalDate startDate = timeline.getFirst();
        LocalDate endDate = timeline.getLast();

        double equity = config.initialEquityUsd();
        double peakEquity = equity;
        double cumSpotPnl = 0.0;
        double cumCarryPnl = 0.0;
        double cumFriction = 0.0;
        int executedTrades = 0;
        int inertiaSkipped = 0;
        double totalTradedNotional = 0.0;

        Map<String, OpenPosition> openPositions = new LinkedHashMap<>();
        List<LedgerSnapshot> curve = new ArrayList<>(timeline.size() - 1);
        List<StageTrace> traces = new ArrayList<>();

        for (int t = 0; t < timeline.size() - 1; t++) {
            LocalDate signalDate = timeline.get(t);
            LocalDate execDate = timeline.get(t + 1);

            Map<String, FxDailyBar> signalBarsToday = barsOnDate(orderedBars, indexByPairAndDate, signalDate);
            Map<String, FxDailyBar> execBarsTomorrow = barsOnDate(orderedBars, indexByPairAndDate, execDate);

            double drawdownPct = peakEquity > 0 ? (equity - peakEquity) / peakEquity * 100.0 : 0.0;
            PortfolioState state = new PortfolioState(
                signalDate,
                equity,
                peakEquity,
                drawdownPct,
                openPositions,
                PortfolioLedger.computeNetCurrencyExposureUsd(openPositions)
            );

            Map<String, Double> signals = new LinkedHashMap<>();
            Map<String, Double> vols = new LinkedHashMap<>();
            for (String pair : signalBarsToday.keySet()) {
                if (!execBarsTomorrow.containsKey(pair)) {
                    continue;
                }
                int idx = indexByPairAndDate.get(pair).get(signalDate);
                List<FxDailyBar> seriesBars = orderedBars.get(pair);
                double signal = signalEngine.computeSignal(ta4jSeries.get(pair), seriesBars, idx);
                double vol = CarverAqrSignalEngine.trailingAnnualizedVol(seriesBars, idx, 63);
                signals.put(pair, signal);
                vols.put(pair, vol);
            }

            Map<String, TargetPosition> targets = riskSizer.sizeTargets(
                signalDate, signals, signalBarsToday, vols, state, config, riskOverlay
            );

            double dailySpotPnl = 0.0;
            double dailyCarryPnl = 0.0;
            double dailyFriction = 0.0;
            double grossNotionalUsd = 0.0;
            Map<String, OpenPosition> nextPositions = new LinkedHashMap<>(openPositions);

            for (Map.Entry<String, TargetPosition> entry : targets.entrySet()) {
                String pair = entry.getKey();
                TargetPosition target = entry.getValue();
                if (target.heldByInertiaBuffer()) {
                    inertiaSkipped++;
                }

                FxDailyBar signalBar = signalBarsToday.get(pair);
                FxDailyBar execBar = execBarsTomorrow.get(pair);
                InstrumentSpec spec = InstrumentCatalog.forPair(pair);

                double prevUnits = openPositions.containsKey(pair) ? openPositions.get(pair).baseUnits() : 0.0;
                double deltaUnits = target.targetBaseUnits() - prevUnits;
                OrderDelta order = new OrderDelta(pair, signalDate, prevUnits, target.targetBaseUnits(), deltaUnits);

                double quoteToUsdOpen = PortfolioLedger.resolveQuoteToUsdAtOpen(spec, execBar.open(), execBarsTomorrow);
                double quoteToUsdClose = PortfolioLedger.resolveQuoteToUsd(spec, execBar.close(), execBarsTomorrow);

                FillReport fill = executionSimulator.simulateFillAndRollover(
                    order, execBar, quoteToUsdOpen, quoteToUsdClose, config
                );

                if (Math.abs(fill.filledDeltaBaseUnits()) > 1e-9) {
                    executedTrades++;
                    totalTradedNotional += Math.abs(fill.filledDeltaBaseUnits()) * execBar.open() * quoteToUsdOpen;
                }

                double pairSpotPnl = PortfolioLedger.computePairSpotPnlUsd(
                    prevUnits, deltaUnits, signalBar, execBar, quoteToUsdClose
                );
                double pairCarryPnl = fill.dailyCarryUsd();

                dailySpotPnl += pairSpotPnl;
                dailyCarryPnl += pairCarryPnl;
                dailyFriction += fill.frictionCostUsd();

                double postNotionalUsd = target.targetBaseUnits() * execBar.close() * quoteToUsdClose;
                if (Math.abs(target.targetBaseUnits()) > 1e-9) {
                    nextPositions.put(pair, new OpenPosition(pair, target.targetBaseUnits(), fill.fillPrice(), postNotionalUsd));
                    grossNotionalUsd += Math.abs(postNotionalUsd);
                } else {
                    nextPositions.remove(pair);
                }

                if (traces.size() < config.traceLimit() && (Math.abs(target.signal()) > 1e-6 || Math.abs(prevUnits) > 1e-6)) {
                    traces.add(new StageTrace(
                        signalDate,
                        execDate,
                        pair,
                        signalBar,
                        computeAtr20(orderedBars.get(pair), indexByPairAndDate.get(pair).get(signalDate)),
                        target.annualizedVolPct(),
                        target.signal(),
                        target,
                        fill,
                        pairSpotPnl,
                        pairCarryPnl
                    ));
                }
            }

            openPositions = nextPositions;
            double netDailyPnl = dailySpotPnl + dailyCarryPnl - dailyFriction;
            equity = Math.max(0.0, equity + netDailyPnl);
            peakEquity = Math.max(peakEquity, equity);
            double postDdPct = peakEquity > 0 ? (equity - peakEquity) / peakEquity * 100.0 : 0.0;
            double grossLeverage = equity > 0 ? grossNotionalUsd / equity : 0.0;

            cumSpotPnl += dailySpotPnl;
            cumCarryPnl += dailyCarryPnl;
            cumFriction += dailyFriction;

            curve.add(new LedgerSnapshot(
                execDate,
                equity,
                dailySpotPnl,
                dailyCarryPnl,
                dailyFriction,
                netDailyPnl,
                cumSpotPnl,
                cumCarryPnl,
                cumFriction,
                postDdPct,
                grossLeverage
            ));
        }

        PipelineSummary summary = PortfolioLedger.summarize(
            config.initialEquityUsd(), curve, executedTrades, inertiaSkipped, totalTradedNotional
        );
        return new PipelineBacktestResult(profileId, startDate, endDate, summary, curve, traces);
    }

    private static Map<String, FxDailyBar> barsOnDate(
        Map<String, List<FxDailyBar>> orderedBars,
        Map<String, Map<LocalDate, Integer>> indexByPairAndDate,
        LocalDate date
    ) {
        Map<String, FxDailyBar> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<FxDailyBar>> entry : orderedBars.entrySet()) {
            Integer idx = indexByPairAndDate.get(entry.getKey()).get(date);
            if (idx != null) {
                result.put(entry.getKey(), entry.getValue().get(idx));
            }
        }
        return result;
    }

    private static double computeAtr20(List<FxDailyBar> bars, int index) {
        int start = Math.max(1, index - 19);
        if (index < 1) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= index; i++) {
            FxDailyBar curr = bars.get(i);
            double prevClose = bars.get(i - 1).close();
            double tr = Math.max(
                curr.high() - curr.low(),
                Math.max(Math.abs(curr.high() - prevClose), Math.abs(curr.low() - prevClose))
            );
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    static BarSeries toBarSeries(String pair, List<FxDailyBar> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName(pair).build();
        for (FxDailyBar bar : bars) {
            Instant endTime = bar.date().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            series.barBuilder()
                .timePeriod(Duration.ofDays(1))
                .endTime(endTime)
                .openPrice(bar.open())
                .highPrice(bar.high())
                .lowPrice(bar.low())
                .closePrice(bar.close())
                .volume(bar.volume())
                .add();
        }
        return series;
    }
}
