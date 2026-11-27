package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.marketdata.OhlcBar;
import com.currencyanalyzer.trade.TradeJournalRepository;
import com.currencyanalyzer.trade.TradeJournalRepository.TradeRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.ta4j.core.BarSeries;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MasterPipelineService {

    public static final List<String> SUPPORTED_UNIVERSE = List.of(
        "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF",
        "AUD/USD", "USD/CAD", "NZD/USD", "GOLD/USD", "SILVER/USD"
    );
    public static final List<String> DEFAULT_BACKTEST_BASKET = List.of(
        "EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "GOLD/USD"
    );

    private static final int COMPUTE_POOL_THREADS = 2;
    private static final long COMPUTE_TIMEOUT_SECONDS = 30L;
    private static final int FULL_TRACE_CAPTURE_LIMIT = 50_000;
    private static final int RETURNED_RECENT_TRACES = 80;
    private static final int LIVE_TARGET_LOOKBACK_CALENDAR_DAYS = 430;
    private static final String ALIGNMENT_ALIGNED = "ALIGNED";
    private static final String ALIGNMENT_DIVERGENT = "DIVERGENT";
    private static final String ALIGNMENT_UNPOSITIONED = "UNPOSITIONED";
    private static final String ALIGNMENT_FLAT = "FLAT";

    private final IncrementalOhlcSyncService ohlcSyncService;
    private final AlignedMarketDataRepository alignedRepository;
    private final FxMasterPipelineRunner pipelineRunner;
    private final VolatilityTargetRiskSizer riskSizer;
    private final RuleBacktestEngine ruleBacktestEngine;
    private final MarketDataService marketDataService;
    private final TradeJournalRepository tradeJournalRepository;
    private final Map<String, MasterStrategyProfile> strategyProfiles;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock;
    private final ExecutorService computeExecutor;

    public MasterPipelineService(
        IncrementalOhlcSyncService ohlcSyncService,
        AlignedMarketDataRepository alignedRepository,
        FxMasterPipelineRunner pipelineRunner,
        VolatilityTargetRiskSizer riskSizer,
        RuleBacktestEngine ruleBacktestEngine,
        MarketDataService marketDataService,
        TradeJournalRepository tradeJournalRepository,
        Map<String, MasterStrategyProfile> strategyProfiles,
        Clock clock
    ) {
        this.ohlcSyncService = ohlcSyncService;
        this.alignedRepository = alignedRepository;
        this.pipelineRunner = pipelineRunner;
        this.riskSizer = riskSizer;
        this.ruleBacktestEngine = ruleBacktestEngine;
        this.marketDataService = marketDataService;
        this.tradeJournalRepository = tradeJournalRepository;
        this.strategyProfiles = strategyProfiles;
        this.clock = clock;
        AtomicInteger counter = new AtomicInteger(1);
        this.computeExecutor = Executors.newFixedThreadPool(COMPUTE_POOL_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "master-pipeline-compute-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdownExecutor() {
        computeExecutor.shutdownNow();
    }

    public Map<String, Object> describeCatalog() {
        List<Map<String, Object>> profiles = strategyProfiles.values().stream()
            .map(profile -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", profile.id());
                item.put("display_name", profile.displayName());
                item.put("signal_engine_id", profile.signalEngine().id());
                item.put("risk_overlay_id", profile.riskOverlay().id());
                item.put("warmup_bars", profile.signalEngine().warmupBars());
                return item;
            })
            .toList();

        PipelineConfig defaults = PipelineConfig.defaults();
        Map<String, Object> defaultCfg = new LinkedHashMap<>();
        defaultCfg.put("initial_equity_usd", defaults.initialEquityUsd());
        defaultCfg.put("target_volatility_pct", round(defaults.targetVolatilityPct() * 100.0, 2));
        defaultCfg.put("max_currency_exposure_multiple", defaults.maxCurrencyExposureMultiple());
        defaultCfg.put("rebalance_inertia_pct", round(defaults.rebalanceInertiaPct() * 100.0, 2));
        defaultCfg.put("broker_carry_markup_pct", round(defaults.brokerCarryMarkupAnnual() * 100.0, 2));
        defaultCfg.put("enable_carry", defaults.enableCarry());

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("success", true);
        catalog.put("default_profile_id", CarverAqrSignalEngine.ENGINE_ID);
        catalog.put("supported_pairs", SUPPORTED_UNIVERSE);
        catalog.put("default_pairs", DEFAULT_BACKTEST_BASKET);
        catalog.put("profiles", profiles);
        catalog.put("default_config", defaultCfg);
        return catalog;
    }

    public Map<String, Object> backtestConditionsSpec() {
        return ruleBacktestEngine.conditionsSpec();
    }

    public Map<String, Object> runSinglePairRuleBacktest(Map<String, Object> payload) {
        if (payload == null || payload.get("pair") == null || payload.get("pair").toString().isBlank()) {
            throw new IllegalArgumentException("pair is required");
        }
        String pair = payload.get("pair").toString().trim().toUpperCase(Locale.ROOT);
        int days = parseBoundedInt(payload.get("days"), 730, 30, 3650, "days");
        List<OhlcBar> bars = marketDataService.fetchHistoricalOhlc(pair, days);
        return runOnComputePool(() -> ruleBacktestEngine.runRuleBacktest(bars, payload));
    }

    public Map<String, Object> runPipelineArena(
        String username,
        boolean persistRun,
        PipelineBacktestRequest request
    ) {
        PipelineBacktestRequest req = request == null ? PipelineBacktestRequest.defaults() : request;
        String primaryProfileId = resolveProfileId(req.profileId());
        List<String> pairs = resolvePairs(req.pairs(), DEFAULT_BACKTEST_BASKET);
        int days = req.days() == null ? 1260 : validateRangeInt(req.days(), 300, 3650, "days");
        boolean includeArena = req.includeArena() == null || req.includeArena();

        PipelineConfig config = buildPipelineConfig(req);
        LocalDate endDate = IncrementalOhlcSyncService.lastCompletedNyTradingDate(clock);
        LocalDate startDate = endDate.minusDays(days);

        Map<String, List<FxDailyBar>> alignedBars = ohlcSyncService.ensureSyncedAndEmitAlignedBars(
            pairs, startDate, endDate
        );
        validateAlignedBars(alignedBars);

        ArenaExecution execution = runOnComputePool(() -> executeArenaOnAlignedBars(
            primaryProfileId, alignedBars, config, includeArena
        ));

        UUID runId = UUID.randomUUID();
        if (persistRun && username != null && !username.isBlank()) {
            persistRunSafely(runId, username, primaryProfileId, execution.primaryResult(), config, pairs);
        }

        return formatArenaResponse(runId, pairs, execution);
    }

    public Map<String, Object> computeLiveTargets(
        String username,
        Double requestedEquityUsd,
        Double requestedTargetVolPct,
        String requestedProfileId,
        List<String> requestedPairs
    ) {
        double equity = requestedEquityUsd != null && Double.isFinite(requestedEquityUsd) && requestedEquityUsd > 0
            ? Math.min(100_000_000.0, requestedEquityUsd)
            : 10_000.0;
        double targetVolDecimal = requestedTargetVolPct != null && Double.isFinite(requestedTargetVolPct)
            && requestedTargetVolPct > 0 && requestedTargetVolPct <= 100.0
            ? requestedTargetVolPct / 100.0
            : 0.10;

        String profileId = resolveProfileId(requestedProfileId);
        MasterStrategyProfile profile = strategyProfiles.get(profileId);
        List<String> pairs = resolvePairs(requestedPairs, SUPPORTED_UNIVERSE);

        LocalDate endDate = IncrementalOhlcSyncService.lastCompletedNyTradingDate(clock);
        LocalDate startDate = endDate.minusDays(LIVE_TARGET_LOOKBACK_CALENDAR_DAYS);
        Map<String, List<FxDailyBar>> alignedBars = ohlcSyncService.ensureSyncedAndEmitAlignedBars(
            pairs, startDate, endDate
        );

        List<TradeRecord> openDiaryTrades = username == null || username.isBlank()
            ? List.of()
            : tradeJournalRepository.findOpenByUsername(username);

        return runOnComputePool(() -> evaluateLiveTargetSheet(
            profile, equity, targetVolDecimal, endDate, pairs, alignedBars, openDiaryTrades
        ));
    }

    public List<Map<String, Object>> listRecentRuns(String username, int limit) {
        return alignedRepository.findRunsByUsername(username, Math.max(1, Math.min(30, limit))).stream()
            .map(run -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", run.id().toString());
                row.put("profile_id", run.profileId());
                row.put("start_date", run.startDate().toString());
                row.put("end_date", run.endDate().toString());
                row.put("config_json", run.configJson());
                row.put("summary_json", run.summaryJson());
                row.put("created_at", run.createdAt().toString());
                return row;
            })
            .toList();
    }

    private ArenaExecution executeArenaOnAlignedBars(
        String primaryProfileId,
        Map<String, List<FxDailyBar>> alignedBars,
        PipelineConfig config,
        boolean includeArena
    ) {
        MasterStrategyProfile primaryProfile = strategyProfiles.get(primaryProfileId);
        PipelineBacktestResult primaryResult = pipelineRunner.run(
            primaryProfile.id(),
            alignedBars,
            primaryProfile.signalEngine(),
            primaryProfile.riskOverlay(),
            config
        );

        List<ArenaEntry> arenaEntries = new ArrayList<>();
        arenaEntries.add(new ArenaEntry(primaryProfile, primaryResult.summary()));

        if (includeArena) {
            PipelineConfig lightConfig = new PipelineConfig(
                config.initialEquityUsd(),
                config.targetVolatilityPct(),
                config.maxCurrencyExposureMultiple(),
                config.rebalanceInertiaPct(),
                config.brokerCarryMarkupAnnual(),
                config.enableCarry(),
                0
            );
            for (MasterStrategyProfile candidate : strategyProfiles.values()) {
                if (candidate.id().equals(primaryProfile.id())) {
                    continue;
                }
                PipelineBacktestResult candidateRes = pipelineRunner.run(
                    candidate.id(),
                    alignedBars,
                    candidate.signalEngine(),
                    candidate.riskOverlay(),
                    lightConfig
                );
                arenaEntries.add(new ArenaEntry(candidate, candidateRes.summary()));
            }
        }
        return new ArenaExecution(primaryProfile, primaryResult, arenaEntries);
    }

    private Map<String, Object> evaluateLiveTargetSheet(
        MasterStrategyProfile profile,
        double equityUsd,
        double targetVolDecimal,
        LocalDate asOfTargetClose,
        List<String> requestedPairs,
        Map<String, List<FxDailyBar>> alignedBars,
        List<TradeRecord> openTrades
    ) {
        PipelineConfig config = new PipelineConfig(equityUsd, targetVolDecimal, 1.50, 0.10, 0.01, true, 0);
        Map<String, FxDailyBar> latestBars = new LinkedHashMap<>();
        Map<String, Double> signals = new LinkedHashMap<>();
        Map<String, Double> vols = new LinkedHashMap<>();
        Map<String, Double> atrs = new LinkedHashMap<>();
        LocalDate latestDateObserved = asOfTargetClose;

        for (String pair : requestedPairs) {
            List<FxDailyBar> seriesBars = alignedBars.get(pair);
            if (seriesBars == null || seriesBars.size() < 20) {
                continue;
            }
            int lastIdx = seriesBars.size() - 1;
            FxDailyBar lastBar = seriesBars.get(lastIdx);
            latestBars.put(pair, lastBar);
            latestDateObserved = lastBar.date();

            BarSeries series = FxMasterPipelineRunner.toBarSeries(pair, seriesBars);
            double sig = profile.signalEngine().computeSignal(series, seriesBars, lastIdx);
            double vol = CarverAqrSignalEngine.trailingAnnualizedVol(seriesBars, lastIdx, 63);
            signals.put(pair, sig);
            vols.put(pair, vol);
            atrs.put(pair, computeAtr14(seriesBars, lastIdx));
        }

        PortfolioState emptyState = new PortfolioState(
            latestDateObserved, equityUsd, equityUsd, 0.0, Map.of(), Map.of()
        );
        double regimeMultiplier = profile.riskOverlay().riskMultiplier(latestDateObserved, emptyState, vols);
        Map<String, TargetPosition> sized = latestBars.isEmpty()
            ? Map.of()
            : riskSizer.sizeTargets(latestDateObserved, signals, latestBars, vols, emptyState, config, profile.riskOverlay());

        Map<String, OpenPosition> targetPositionsMap = new LinkedHashMap<>();
        for (Map.Entry<String, TargetPosition> e : sized.entrySet()) {
            TargetPosition tp = e.getValue();
            if (Math.abs(tp.targetNotionalUsd()) > 1e-6) {
                FxDailyBar bar = latestBars.get(e.getKey());
                targetPositionsMap.put(e.getKey(), new OpenPosition(
                    e.getKey(), tp.targetBaseUnits(), bar.close(), tp.targetNotionalUsd()
                ));
            }
        }
        Map<String, Double> netCurrencyExposure = PortfolioLedger.computeNetCurrencyExposureUsd(targetPositionsMap);
        double netUsdExposure = netCurrencyExposure.getOrDefault("USD", 0.0);
        double maxClusterCapUsd = equityUsd * config.maxCurrencyExposureMultiple();

        Map<String, List<TradeRecord>> openByPair = groupOpenTradesByPair(openTrades);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String pair : requestedPairs) {
            TargetPosition tp = sized.get(pair);
            if (tp == null) {
                continue;
            }
            rows.add(buildLiveTargetRow(
                pair,
                tp,
                latestBars.get(pair),
                atrs.getOrDefault(pair, 0.0),
                config,
                openByPair.getOrDefault(pair, List.of())
            ));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("profile_id", profile.id());
        response.put("profile_name", profile.displayName());
        response.put("as_of_date", latestDateObserved.toString());
        response.put("equity_usd", round(equityUsd, 2));
        response.put("target_volatility_pct", round(targetVolDecimal * 100.0, 2));
        response.put("regime_multiplier", round(regimeMultiplier, 3));
        response.put("net_usd_exposure_usd", round(netUsdExposure, 2));
        response.put("max_usd_cluster_cap_usd", round(maxClusterCapUsd, 2));
        response.put("usd_cluster_headroom_usd", round(Math.max(0.0, maxClusterCapUsd - Math.abs(netUsdExposure)), 2));
        response.put("targets", rows);
        return response;
    }

    private static Map<String, Object> buildLiveTargetRow(
        String pair,
        TargetPosition tp,
        FxDailyBar bar,
        double atr14,
        PipelineConfig config,
        List<TradeRecord> userPairTrades
    ) {
        double grossCarryPct = bar.basePolicyRatePct() - bar.quotePolicyRatePct();
        double directionSign = Math.signum(tp.signal());
        double netDirectionalCarryPct = Math.abs(directionSign) < 1e-6
            ? 0.0
            : (directionSign * grossCarryPct) - (config.brokerCarryMarkupAnnual() * 100.0);
        double inertiaBandUsd = Math.abs(tp.targetNotionalUsd()) * config.rebalanceInertiaPct();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pair", pair);
        row.put("bar_date", bar.date().toString());
        row.put("close", round(bar.close(), 4));
        row.put("atr_14", round(atr14, 4));
        row.put("signal", round(tp.signal(), 3));
        row.put("direction", describeDirection(tp.signal()));
        row.put("annualized_vol_pct", round(tp.annualizedVolPct(), 2));
        row.put("base_rate_pct", round(bar.basePolicyRatePct(), 2));
        row.put("quote_rate_pct", round(bar.quotePolicyRatePct(), 2));
        row.put("net_carry_annual_pct", round(netDirectionalCarryPct, 2));
        row.put("target_notional_usd", round(tp.targetNotionalUsd(), 2));
        row.put("target_base_units", round(tp.targetBaseUnits(), 4));
        row.put("inertia_band_usd", round(inertiaBandUsd, 2));
        row.put("capped_by_cluster", tp.cappedByExposureLimit());
        row.put("discretionary_alignment", classifyAlignment(tp.signal(), userPairTrades));
        row.put("open_discretionary_count", userPairTrades.size());
        return row;
    }

    private static String describeDirection(double signal) {
        if (signal > 0.05) {
            return "LONG";
        }
        if (signal < -0.05) {
            return "SHORT";
        }
        return "FLAT";
    }

    private static Map<String, List<TradeRecord>> groupOpenTradesByPair(List<TradeRecord> trades) {
        Map<String, List<TradeRecord>> map = new LinkedHashMap<>();
        for (TradeRecord trade : trades) {
            map.computeIfAbsent(trade.pair(), ignored -> new ArrayList<>()).add(trade);
        }
        return map;
    }

    private static String classifyAlignment(double masterSignal, List<TradeRecord> openTrades) {
        boolean masterActive = Math.abs(masterSignal) >= 0.05;
        if (openTrades.isEmpty()) {
            return masterActive ? ALIGNMENT_UNPOSITIONED : ALIGNMENT_FLAT;
        }
        double netUnits = 0.0;
        for (TradeRecord trade : openTrades) {
            double qty = trade.quantity() != null ? trade.quantity() : 0.0;
            netUnits += "short".equalsIgnoreCase(trade.side()) ? -qty : qty;
        }
        if (!masterActive) {
            return Math.abs(netUnits) > 1e-9 ? ALIGNMENT_DIVERGENT : ALIGNMENT_FLAT;
        }
        return Math.signum(netUnits) == Math.signum(masterSignal) ? ALIGNMENT_ALIGNED : ALIGNMENT_DIVERGENT;
    }

    private static double computeAtr14(List<FxDailyBar> bars, int endIdx) {
        int start = Math.max(1, endIdx - 13);
        if (endIdx < 1) {
            return 0.0;
        }
        double sum = 0.0;
        int count = 0;
        for (int i = start; i <= endIdx; i++) {
            FxDailyBar b = bars.get(i);
            double prevClose = bars.get(i - 1).close();
            double tr = Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - prevClose), Math.abs(b.low() - prevClose)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    private Map<String, Object> formatArenaResponse(UUID runId, List<String> pairs, ArenaExecution execution) {
        PipelineBacktestResult primary = execution.primaryResult();
        List<StageTrace> allTraces = primary.traces();
        List<StageTrace> tailTraces = allTraces.size() <= RETURNED_RECENT_TRACES
            ? allTraces
            : allTraces.subList(allTraces.size() - RETURNED_RECENT_TRACES, allTraces.size());

        List<Map<String, Object>> curve = primary.equityCurve().stream()
            .map(snap -> {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("date", snap.date().toString());
                pt.put("equity_usd", round(snap.equityUsd(), 2));
                pt.put("cum_spot_pnl_usd", round(snap.cumulativeSpotPnlUsd(), 2));
                pt.put("cum_carry_pnl_usd", round(snap.cumulativeCarryPnlUsd(), 2));
                pt.put("cum_friction_usd", round(snap.cumulativeFrictionCostUsd(), 2));
                pt.put("drawdown_pct", round(snap.drawdownPct(), 2));
                pt.put("gross_leverage", round(snap.grossLeverage(), 2));
                return pt;
            })
            .toList();

        List<Map<String, Object>> traceRows = new ArrayList<>(tailTraces.size());
        for (int i = tailTraces.size() - 1; i >= 0; i--) {
            StageTrace tr = tailTraces.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("signal_date", tr.signalDate().toString());
            item.put("exec_date", tr.executionDate().toString());
            item.put("pair", tr.pair());
            item.put("close", round(tr.signalBar().close(), 4));
            item.put("atr_20", round(tr.atr20(), 4));
            item.put("annualized_vol_pct", round(tr.annualizedVolPct(), 2));
            item.put("signal", round(tr.signal(), 3));
            item.put("target_notional_usd", round(tr.target().targetNotionalUsd(), 2));
            item.put("capped_by_cluster", tr.target().cappedByExposureLimit());
            item.put("held_by_inertia", tr.target().heldByInertiaBuffer());
            item.put("fill_open_price", round(tr.fill().fillPrice(), 4));
            item.put("filled_delta_units", round(tr.fill().filledDeltaBaseUnits(), 2));
            item.put("friction_usd", round(tr.fill().frictionCostUsd(), 2));
            item.put("rollover_days", tr.fill().rolloverDays());
            item.put("spot_pnl_usd", round(tr.pairSpotPnlUsd(), 2));
            item.put("carry_pnl_usd", round(tr.pairCarryPnlUsd(), 2));
            traceRows.add(item);
        }

        List<Map<String, Object>> arenaRows = execution.arenaEntries().stream()
            .map(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("profile_id", entry.profile().id());
                row.put("display_name", entry.profile().displayName());
                row.put("summary", summaryToMap(entry.summary()));
                return row;
            })
            .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("run_id", runId.toString());
        out.put("profile_id", execution.primaryProfile().id());
        out.put("profile_name", execution.primaryProfile().displayName());
        out.put("pairs", pairs);
        out.put("start_date", primary.startDate().toString());
        out.put("end_date", primary.endDate().toString());
        out.put("summary", summaryToMap(primary.summary()));
        out.put("arena_comparison", arenaRows);
        out.put("equity_curve", curve);
        out.put("recent_traces", Collections.unmodifiableList(traceRows));
        return out;
    }

    static Map<String, Object> summaryToMap(PipelineSummary s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("initial_equity_usd", round(s.initialEquityUsd(), 2));
        map.put("final_equity_usd", round(s.finalEquityUsd(), 2));
        map.put("total_return_pct", round(s.totalReturnPct(), 2));
        map.put("cagr_pct", round(s.cagrPct(), 2));
        map.put("annualized_vol_pct", round(s.annualizedVolPct(), 2));
        map.put("net_sharpe_ratio", round(s.netSharpeRatio(), 2));
        map.put("spot_only_sharpe_ratio", round(s.spotOnlySharpeRatio(), 2));
        map.put("sortino_ratio", round(s.sortinoRatio(), 2));
        map.put("calmar_ratio", round(s.calmarRatio(), 2));
        map.put("max_drawdown_pct", round(s.maxDrawdownPct(), 2));
        map.put("var_95_daily_pct", round(s.historicalVar95DailyPct(), 2));
        map.put("total_spot_pnl_usd", round(s.totalSpotPnlUsd(), 2));
        map.put("total_carry_pnl_usd", round(s.totalCarryPnlUsd(), 2));
        map.put("total_friction_usd", round(s.totalFrictionCostUsd(), 2));
        map.put("executed_trades_count", s.executedTradesCount());
        map.put("inertia_skipped_count", s.inertiaSkippedCount());
        map.put("annualized_turnover", round(s.annualizedTurnover(), 2));
        return map;
    }

    private void persistRunSafely(
        UUID runId,
        String username,
        String profileId,
        PipelineBacktestResult result,
        PipelineConfig config,
        List<String> pairs
    ) {
        try {
            String configJson = objectMapper.writeValueAsString(Map.of(
                "pairs", pairs,
                "initialEquityUsd", config.initialEquityUsd(),
                "targetVolatilityPct", config.targetVolatilityPct(),
                "maxCurrencyExposureMultiple", config.maxCurrencyExposureMultiple(),
                "rebalanceInertiaPct", config.rebalanceInertiaPct(),
                "enableCarry", config.enableCarry()
            ));
            String summaryJson = objectMapper.writeValueAsString(summaryToMap(result.summary()));
            alignedRepository.saveBacktestRun(
                runId,
                username,
                profileId,
                result.startDate(),
                result.endDate(),
                configJson,
                summaryJson,
                clock.instant()
            );
        } catch (JsonProcessingException ignored) {
            // Non-fatal serialization fallback
        }
    }

    private String resolveProfileId(String rawProfileId) {
        String candidate = (rawProfileId == null || rawProfileId.isBlank())
            ? CarverAqrSignalEngine.ENGINE_ID
            : rawProfileId.trim();
        if (!strategyProfiles.containsKey(candidate)) {
            throw new IllegalArgumentException("Unknown Master strategy profile: " + candidate);
        }
        return candidate;
    }

    private static List<String> resolvePairs(List<String> rawPairs, List<String> fallback) {
        if (rawPairs == null || rawPairs.isEmpty()) {
            return fallback;
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : rawPairs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            InstrumentSpec spec = InstrumentCatalog.forPair(raw);
            if (!normalized.contains(spec.pair())) {
                normalized.add(spec.pair());
            }
        }
        if (normalized.isEmpty() || normalized.size() > 12) {
            throw new IllegalArgumentException("Select between 1 and 12 valid FX/Metals pairs");
        }
        return List.copyOf(normalized);
    }

    private static PipelineConfig buildPipelineConfig(PipelineBacktestRequest req) {
        double equity = req.initialEquityUsd() != null ? req.initialEquityUsd() : 100_000.0;
        double targetVol = req.targetVolatilityPct() != null ? req.targetVolatilityPct() / 100.0 : 0.10;
        double maxExp = req.maxCurrencyExposureMultiple() != null ? req.maxCurrencyExposureMultiple() : 1.50;
        double inertia = req.rebalanceInertiaPct() != null ? req.rebalanceInertiaPct() / 100.0 : 0.10;
        double markup = req.brokerCarryMarkupPct() != null ? req.brokerCarryMarkupPct() / 100.0 : 0.01;
        boolean enableCarry = req.enableCarry() == null || req.enableCarry();
        return new PipelineConfig(equity, targetVol, maxExp, inertia, markup, enableCarry, FULL_TRACE_CAPTURE_LIMIT);
    }

    private static void validateAlignedBars(Map<String, List<FxDailyBar>> alignedBars) {
        boolean hasValidSeries = alignedBars != null && alignedBars.values().stream()
            .anyMatch(list -> list != null && list.size() >= 65);
        if (!hasValidSeries) {
            throw new IllegalArgumentException(
                "Insufficient aligned OHLC history available for the selected pairs (need >= 65 daily bars)"
            );
        }
    }

    private static int parseBoundedInt(Object raw, int fallback, int min, int max, String field) {
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        int parsed;
        if (raw instanceof Number n) {
            parsed = n.intValue();
        } else {
            try {
                parsed = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + " must be an integer", exception);
            }
        }
        return validateRangeInt(parsed, min, max, field);
    }

    private static int validateRangeInt(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private <T> T runOnComputePool(Callable<T> task) {
        try {
            return computeExecutor.submit(task).get(COMPUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Pipeline computation failed", cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pipeline computation interrupted", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("Pipeline computation timed out", exception);
        }
    }

    private static double round(double value, int decimals) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double scale = Math.pow(10.0, decimals);
        return Math.round(value * scale) / scale;
    }

    private record ArenaEntry(MasterStrategyProfile profile, PipelineSummary summary) {
    }

    private record ArenaExecution(
        MasterStrategyProfile primaryProfile,
        PipelineBacktestResult primaryResult,
        List<ArenaEntry> arenaEntries
    ) {
    }

    public record PipelineBacktestRequest(
        String profileId,
        List<String> pairs,
        Integer days,
        Double initialEquityUsd,
        Double targetVolatilityPct,
        Double maxCurrencyExposureMultiple,
        Double rebalanceInertiaPct,
        Double brokerCarryMarkupPct,
        Boolean enableCarry,
        Boolean includeArena
    ) {
        public static PipelineBacktestRequest defaults() {
            return new PipelineBacktestRequest(
                CarverAqrSignalEngine.ENGINE_ID,
                DEFAULT_BACKTEST_BASKET,
                1260,
                100_000.0,
                10.0,
                1.50,
                10.0,
                1.0,
                true,
                true
            );
        }
    }
}
