package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.OhlcBar;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single-pair rule backtest engine backing {@code GET /api/backtest/conditions}
 * and {@code POST /api/backtest} for the Strategy & AI Lab rule builder, executing
 * entries and signal exits strictly at Day {@code t+1} Open to prevent look-ahead bias.
 */
public class RuleBacktestEngine {

    private static final String SPEC_TYPE = "type";
    private static final String SPEC_MIN = "min";
    private static final String SPEC_MAX = "max";
    private static final String SPEC_STEP = "step";
    private static final String SPEC_DEFAULT = "default";
    private static final String SPEC_UNIT = "unit";
    private static final String SPEC_OPTIONS = "options";

    private static final String RULE_LONG_TERM_UPTREND = "long_term_uptrend";
    private static final String RULE_PERCENTAGE_CHANGE = "percentage_change";
    private static final String RULE_MOVING_AVERAGE = "moving_average";
    private static final String RULE_MA_CROSSOVER_ALIAS = "moving_average_crossover";
    private static final String RULE_PRICE_LEVEL = "price_level";
    private static final String RULE_PRICE_THRESHOLD_ALIAS = "price_threshold";

    private static final Map<String, Object> BACKTEST_CONDITIONS = buildConditionsSpec();

    public Map<String, Object> conditionsSpec() {
        return BACKTEST_CONDITIONS;
    }

    public Map<String, Object> runRuleBacktest(List<OhlcBar> rawBars, Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Backtest payload is required");
        }
        String pair = requireNonBlank(payload.get("pair"), "pair").toUpperCase(Locale.ROOT);
        double initialCapital = parseDouble(payload.get("initial_capital"), 10_000.0);
        if (initialCapital <= 0 || initialCapital > 1_000_000_000.0) {
            throw new IllegalArgumentException("initial_capital must be positive");
        }
        boolean allowMultiple = parseBoolean(payload.get("allow_multiple_trades"), true);

        Map<String, Object> entrySpec = requireMap(payload.get("entry"), "entry");
        String entryType = normalizeRuleType(requireNonBlank(entrySpec.get(SPEC_TYPE), "entry.type"));

        Map<String, Object> exitSpec = payload.get("exit") instanceof Map<?, ?> rawExit
            ? castMap(rawExit)
            : Map.of();
        int maxHoldingDays = Math.max(1, Math.min(3650, parseInt(exitSpec.get("max_holding_days"), 60)));
        double stopLossPct = Math.max(0.0, Math.min(99.0, parseDouble(exitSpec.get("stop_loss_pct"), 5.0)));
        double takeProfitPct = Math.max(0.0, Math.min(1000.0, parseDouble(exitSpec.get("take_profit_pct"), 10.0)));

        Map<String, Object> exitSignalSpec = exitSpec.get("signal") instanceof Map<?, ?> rawSignal
            ? castMap(rawSignal)
            : null;
        String exitSignalType = exitSignalSpec != null && exitSignalSpec.get(SPEC_TYPE) != null
            ? normalizeRuleType(exitSignalSpec.get(SPEC_TYPE).toString())
            : null;

        List<OhlcBar> bars = sanitizeBars(rawBars);
        if (bars.size() < 20) {
            throw new IllegalArgumentException("Insufficient OHLC history for backtest (at least 20 daily bars required)");
        }

        List<CompletedTrade> trades = simulateTrades(
            bars, entryType, entrySpec, maxHoldingDays, stopLossPct, takeProfitPct,
            exitSignalType, exitSignalSpec, allowMultiple
        );

        Map<String, Object> summary = summarizeTrades(bars, initialCapital, trades);
        List<Map<String, Object>> tradeRows = trades.stream().map(CompletedTrade::toMap).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("pair", pair);
        response.put("summary", summary);
        response.put("trades", tradeRows);
        return response;
    }

    private static List<CompletedTrade> simulateTrades(
        List<OhlcBar> bars,
        String entryType,
        Map<String, Object> entrySpec,
        int maxHoldingDays,
        double stopLossPct,
        double takeProfitPct,
        String exitSignalType,
        Map<String, Object> exitSignalSpec,
        boolean allowMultiple
    ) {
        List<CompletedTrade> completed = new ArrayList<>();
        boolean inPosition = false;
        int entryIndex = -1;
        double entryPrice = 0.0;
        String entryDate = null;

        for (int i = 1; i < bars.size() - 1; i++) {
            if (!inPosition) {
                if (!allowMultiple && !completed.isEmpty()) {
                    break;
                }
                if (matchesRule(bars, i, entryType, entrySpec, false)) {
                    OhlcBar nextBar = bars.get(i + 1);
                    inPosition = true;
                    entryIndex = i + 1;
                    entryPrice = nextBar.open();
                    entryDate = nextBar.date();
                }
                continue;
            }

            if (i >= entryIndex) {
                CompletedTrade closed = tryExitPosition(
                    bars, i, entryIndex, entryDate, entryPrice,
                    maxHoldingDays, stopLossPct, takeProfitPct, exitSignalType, exitSignalSpec
                );
                if (closed != null) {
                    completed.add(closed);
                    inPosition = false;
                }
            }
        }

        if (inPosition && entryIndex >= 0 && entryIndex < bars.size()) {
            OhlcBar lastBar = bars.getLast();
            completed.add(buildTrade(entryDate, entryPrice, lastBar.date(), lastBar.close(), "end_of_window"));
        }
        return completed;
    }

    private static CompletedTrade tryExitPosition(
        List<OhlcBar> bars,
        int i,
        int entryIndex,
        String entryDate,
        double entryPrice,
        int maxHoldingDays,
        double stopLossPct,
        double takeProfitPct,
        String exitSignalType,
        Map<String, Object> exitSignalSpec
    ) {
        OhlcBar bar = bars.get(i);
        double stopPrice = stopLossPct > 0 ? entryPrice * (1.0 - stopLossPct / 100.0) : -1.0;
        if (stopLossPct > 0 && bar.low() <= stopPrice) {
            return buildTrade(entryDate, entryPrice, bar.date(), Math.min(bar.open(), stopPrice), "stop_loss");
        }
        double targetPrice = takeProfitPct > 0 ? entryPrice * (1.0 + takeProfitPct / 100.0) : Double.POSITIVE_INFINITY;
        if (takeProfitPct > 0 && bar.high() >= targetPrice) {
            return buildTrade(entryDate, entryPrice, bar.date(), Math.max(bar.open(), targetPrice), "take_profit");
        }
        if (exitSignalType != null && matchesRule(bars, i, exitSignalType, exitSignalSpec, true)) {
            OhlcBar nextBar = bars.get(i + 1);
            return buildTrade(entryDate, entryPrice, nextBar.date(), nextBar.open(), "exit_signal");
        }
        if (i - entryIndex + 1 >= maxHoldingDays) {
            OhlcBar nextBar = bars.get(i + 1);
            return buildTrade(entryDate, entryPrice, nextBar.date(), nextBar.open(), "max_holding_days");
        }
        return null;
    }

    private static boolean matchesRule(
        List<OhlcBar> bars,
        int index,
        String ruleType,
        Map<String, Object> params,
        boolean forExit
    ) {
        return switch (ruleType) {
            case RULE_LONG_TERM_UPTREND -> evaluateLongTermUptrend(bars, index, params, forExit);
            case RULE_PERCENTAGE_CHANGE -> evaluatePercentageChange(bars, index, params, forExit);
            case RULE_MOVING_AVERAGE -> evaluateMovingAverage(bars, index, params, forExit);
            case RULE_PRICE_LEVEL -> evaluatePriceLevel(bars, index, params);
            default -> throw new IllegalArgumentException("Unsupported rule type: " + ruleType);
        };
    }

    private static boolean evaluateLongTermUptrend(
        List<OhlcBar> bars,
        int index,
        Map<String, Object> params,
        boolean forExit
    ) {
        int trendPeriod = Math.max(10, Math.min(400, parseInt(params.get("trend_period"), 60)));
        double minReturnPct = parseDouble(params.get("min_return_pct"), 2.0);
        boolean requireAboveSma = parseBoolean(params.get("enable_trend_consistency"), true);
        if (index < trendPeriod) {
            return false;
        }
        double pastClose = bars.get(index - trendPeriod).close();
        double currClose = bars.get(index).close();
        if (pastClose <= 0) {
            return false;
        }
        double retPct = (currClose - pastClose) / pastClose * 100.0;
        double sma = simpleMovingAverage(bars, index, trendPeriod);
        if (forExit) {
            return currClose < sma && retPct < 0.0;
        }
        boolean prevTriggered = index > trendPeriod && ((bars.get(index - 1).close() - bars.get(index - 1 - trendPeriod).close())
            / bars.get(index - 1 - trendPeriod).close() * 100.0) >= minReturnPct;
        return !prevTriggered && retPct >= minReturnPct && (!requireAboveSma || currClose >= sma);
    }

    private static boolean evaluatePercentageChange(
        List<OhlcBar> bars,
        int index,
        Map<String, Object> params,
        boolean forExit
    ) {
        int period = Math.max(1, Math.min(365, parseInt(params.get("custom_period"), 14)));
        double thresholdPct = Math.max(0.1, parseDouble(params.get("custom_threshold"), 2.0));
        if (index < period) {
            return false;
        }
        double past = bars.get(index - period).close();
        double curr = bars.get(index).close();
        if (past <= 0) {
            return false;
        }
        double pctMove = (curr - past) / past * 100.0;
        return forExit ? pctMove <= -thresholdPct : pctMove >= thresholdPct;
    }

    private static boolean evaluateMovingAverage(
        List<OhlcBar> bars,
        int index,
        Map<String, Object> params,
        boolean forExit
    ) {
        int shortPeriod = Math.max(2, Math.min(200, parseInt(params.get("ma_short_period"), 10)));
        int longPeriod = Math.max(shortPeriod + 1, Math.min(500, parseInt(params.get("ma_long_period"), 50)));
        String signalType = params.get("signal_type") != null
            ? params.get("signal_type").toString().trim().toLowerCase(Locale.ROOT)
            : (forExit ? "death_cross" : "golden_cross");
        if (index < longPeriod) {
            return false;
        }
        double prevShort = simpleMovingAverage(bars, index - 1, shortPeriod);
        double prevLong = simpleMovingAverage(bars, index - 1, longPeriod);
        double currShort = simpleMovingAverage(bars, index, shortPeriod);
        double currLong = simpleMovingAverage(bars, index, longPeriod);

        boolean goldenCross = prevShort <= prevLong && currShort > currLong;
        boolean deathCross = prevShort >= prevLong && currShort < currLong;
        return switch (signalType) {
            case "golden_cross" -> goldenCross;
            case "death_cross" -> deathCross;
            default -> forExit ? deathCross : goldenCross;
        };
    }

    private static boolean evaluatePriceLevel(List<OhlcBar> bars, int index, Map<String, Object> params) {
        double high = parseDouble(params.get("price_high"), 0.0);
        double low = parseDouble(params.get("price_low"), 0.0);
        String trigger = params.get("trigger_type") != null
            ? params.get("trigger_type").toString().trim().toLowerCase(Locale.ROOT)
            : "above";
        double prev = bars.get(index - 1).close();
        double curr = bars.get(index).close();
        return switch (trigger) {
            case "below" -> low > 0 && prev >= low && curr < low;
            case "outside_range" -> (high > 0 && prev <= high && curr > high) || (low > 0 && prev >= low && curr < low);
            default -> high > 0 && prev <= high && curr > high;
        };
    }

    private static double simpleMovingAverage(List<OhlcBar> bars, int endIdx, int window) {
        int start = Math.max(0, endIdx - window + 1);
        double sum = 0.0;
        for (int i = start; i <= endIdx; i++) {
            sum += bars.get(i).close();
        }
        return sum / (endIdx - start + 1);
    }

    private static CompletedTrade buildTrade(
        String entryDate,
        double entryPrice,
        String exitDate,
        double exitPrice,
        String reason
    ) {
        double pnlPct = entryPrice > 0 ? (exitPrice - entryPrice) / entryPrice * 100.0 : 0.0;
        long days = Math.max(1L, ChronoUnit.DAYS.between(LocalDate.parse(entryDate), LocalDate.parse(exitDate)));
        return new CompletedTrade(
            entryDate,
            round(entryPrice, 4),
            exitDate,
            round(exitPrice, 4),
            round(pnlPct, 2),
            (int) days,
            reason
        );
    }

    private static Map<String, Object> summarizeTrades(
        List<OhlcBar> bars,
        double initialCapital,
        List<CompletedTrade> trades
    ) {
        int numTrades = trades.size();
        double equity = initialCapital;
        double peak = initialCapital;
        double maxDrawdownPct = 0.0;
        int wins = 0;
        int losses = 0;
        double sumWinPct = 0.0;
        double sumLossPct = 0.0;
        double grossProfitPct = 0.0;
        double grossLossAbsPct = 0.0;

        for (CompletedTrade trade : trades) {
            double pnl = trade.pnlPct();
            equity = Math.max(0.0, equity * (1.0 + pnl / 100.0));
            peak = Math.max(peak, equity);
            if (peak > 0) {
                maxDrawdownPct = Math.min(maxDrawdownPct, (equity - peak) / peak * 100.0);
            }
            if (pnl >= 0) {
                wins++;
                sumWinPct += pnl;
                grossProfitPct += pnl;
            } else {
                losses++;
                sumLossPct += pnl;
                grossLossAbsPct += Math.abs(pnl);
            }
        }

        double totalReturnPct = (equity - initialCapital) / initialCapital * 100.0;
        long spanDays = Math.max(1L, ChronoUnit.DAYS.between(
            LocalDate.parse(bars.getFirst().date()),
            LocalDate.parse(bars.getLast().date())
        ));
        double years = Math.max(spanDays / 365.25, 0.1);
        double cagrPct = equity > 0 ? (Math.pow(equity / initialCapital, 1.0 / years) - 1.0) * 100.0 : -100.0;

        double winRatePct = numTrades > 0 ? (wins * 100.0) / numTrades : 0.0;
        double avgTradePct = numTrades > 0 ? (sumWinPct + sumLossPct) / numTrades : 0.0;
        double avgWinPct = wins > 0 ? sumWinPct / wins : 0.0;
        double avgLossPct = losses > 0 ? sumLossPct / losses : 0.0;
        Double profitFactor = grossLossAbsPct > 1e-9 ? round(grossProfitPct / grossLossAbsPct, 2) : (wins > 0 ? 99.0 : 0.0);
        Double payoffRatio = losses > 0 && Math.abs(avgLossPct) > 1e-9 ? round(avgWinPct / Math.abs(avgLossPct), 2) : 0.0;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("num_trades", numTrades);
        summary.put("win_rate_pct", round(winRatePct, 2));
        summary.put("avg_trade_pnl_pct", round(avgTradePct, 2));
        summary.put("avg_win_pnl_pct", round(avgWinPct, 2));
        summary.put("avg_loss_pnl_pct", round(avgLossPct, 2));
        summary.put("profit_factor", profitFactor);
        summary.put("payoff_ratio", payoffRatio);
        summary.put("total_return_pct", round(totalReturnPct, 2));
        summary.put("cagr_pct", round(cagrPct, 2));
        summary.put("final_equity", round(equity, 2));
        summary.put("max_drawdown_pct", round(maxDrawdownPct, 2));
        return summary;
    }

    private static List<OhlcBar> sanitizeBars(List<OhlcBar> rawBars) {
        if (rawBars == null || rawBars.isEmpty()) {
            return List.of();
        }
        List<OhlcBar> sorted = new ArrayList<>();
        for (OhlcBar bar : rawBars) {
            if (bar != null && bar.date() != null
                && Double.isFinite(bar.open()) && bar.open() > 0
                && Double.isFinite(bar.high()) && bar.high() > 0
                && Double.isFinite(bar.low()) && bar.low() > 0
                && Double.isFinite(bar.close()) && bar.close() > 0) {
                sorted.add(bar);
            }
        }
        sorted.sort((a, b) -> a.date().compareTo(b.date()));
        return sorted;
    }

    private static String normalizeRuleType(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (RULE_MA_CROSSOVER_ALIAS.equals(key)) {
            return RULE_MOVING_AVERAGE;
        }
        if (RULE_PRICE_THRESHOLD_ALIAS.equals(key)) {
            return RULE_PRICE_LEVEL;
        }
        if (RULE_LONG_TERM_UPTREND.equals(key)
            || RULE_PERCENTAGE_CHANGE.equals(key)
            || RULE_MOVING_AVERAGE.equals(key)
            || RULE_PRICE_LEVEL.equals(key)) {
            return key;
        }
        throw new IllegalArgumentException("Unsupported backtest rule type: " + raw);
    }

    private static Map<String, Object> buildConditionsSpec() {
        Map<String, Object> uptrendParams = new LinkedHashMap<>();
        uptrendParams.put("trend_period", numSpec(10, 252, 5, 60, "days"));
        uptrendParams.put("min_return_pct", numSpec(0.5, 50.0, 0.5, 2.0, "%"));
        uptrendParams.put("enable_trend_consistency", Map.of(SPEC_TYPE, "boolean", SPEC_DEFAULT, true));

        Map<String, Object> pctParams = new LinkedHashMap<>();
        pctParams.put("custom_threshold", numSpec(0.1, 50.0, 0.1, 2.0, "%"));
        pctParams.put("custom_period", numSpec(1, 365, 1, 14, "days"));

        Map<String, Object> maParams = new LinkedHashMap<>();
        maParams.put("ma_short_period", numSpec(2, 200, 1, 10, "days"));
        maParams.put("ma_long_period", numSpec(5, 500, 5, 50, "days"));
        maParams.put("signal_type", Map.of(
            SPEC_TYPE, "select",
            SPEC_OPTIONS, List.of("golden_cross", "death_cross", "both"),
            SPEC_DEFAULT, "golden_cross"
        ));

        Map<String, Object> priceParams = new LinkedHashMap<>();
        priceParams.put("price_high", numSpec(0, 1_000_000, 0.1, 0, "rate"));
        priceParams.put("price_low", numSpec(0, 1_000_000, 0.1, 0, "rate"));
        priceParams.put("trigger_type", Map.of(
            SPEC_TYPE, "select",
            SPEC_OPTIONS, List.of("above", "below", "outside_range"),
            SPEC_DEFAULT, "above"
        ));

        Map<String, Object> specs = new LinkedHashMap<>();
        specs.put(RULE_LONG_TERM_UPTREND, Map.of(
            "name", "Long-Term Uptrend",
            "description", "Enter when multi-week momentum exceeds threshold above SMA.",
            "parameters", Collections.unmodifiableMap(uptrendParams)
        ));
        specs.put(RULE_PERCENTAGE_CHANGE, Map.of(
            "name", "Percentage Change",
            "description", "Enter on rolling percentage breakout over lookback days.",
            "parameters", Collections.unmodifiableMap(pctParams)
        ));
        specs.put(RULE_MOVING_AVERAGE, Map.of(
            "name", "Moving Average Crossover",
            "description", "Enter or exit when short SMA crosses long SMA.",
            "parameters", Collections.unmodifiableMap(maParams)
        ));
        specs.put(RULE_PRICE_LEVEL, Map.of(
            "name", "Price Level Breakout",
            "description", "Trigger when daily close crosses configured price bounds.",
            "parameters", Collections.unmodifiableMap(priceParams)
        ));
        return Collections.unmodifiableMap(specs);
    }

    private static Map<String, Object> numSpec(Number min, Number max, Number step, Number def, String unit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(SPEC_TYPE, "number");
        m.put(SPEC_MIN, min);
        m.put(SPEC_MAX, max);
        m.put(SPEC_STEP, step);
        m.put(SPEC_DEFAULT, def);
        m.put(SPEC_UNIT, unit);
        return Collections.unmodifiableMap(m);
    }

    private static String requireNonBlank(Object raw, String field) {
        if (raw == null || raw.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return raw.toString().trim();
    }

    private static Map<String, Object> requireMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " object is required");
        }
        return castMap(map);
    }

    private static Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return out;
    }

    private static double parseDouble(Object raw, double fallback) {
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        if (raw instanceof Number number) {
            double v = number.doubleValue();
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Numeric parameter must be finite");
            }
            return v;
        }
        try {
            double v = Double.parseDouble(raw.toString().trim());
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Numeric parameter must be finite");
            }
            return v;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid numeric value: " + raw, exception);
        }
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw == null || raw.toString().isBlank()) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer value: " + raw, exception);
        }
    }

    private static boolean parseBoolean(Object raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(raw.toString().trim());
    }

    private static double round(double value, int decimals) {
        double scale = Math.pow(10.0, decimals);
        return Math.round(value * scale) / scale;
    }

    private record CompletedTrade(
        String entryDate,
        double entryPrice,
        String exitDate,
        double exitPrice,
        double pnlPct,
        int holdingDays,
        String exitReason
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("entry_date", entryDate);
            map.put("entry_price", entryPrice);
            map.put("exit_date", exitDate);
            map.put("exit_price", exitPrice);
            map.put("pnl_pct", pnlPct);
            map.put("holding_days", holdingDays);
            map.put("exit_reason", exitReason);
            return map;
        }
    }
}
