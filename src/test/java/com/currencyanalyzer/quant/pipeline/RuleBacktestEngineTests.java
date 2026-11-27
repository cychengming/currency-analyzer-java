package com.currencyanalyzer.quant.pipeline;

import com.currencyanalyzer.marketdata.OhlcBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBacktestEngineTests {

    private final RuleBacktestEngine engine = new RuleBacktestEngine();

    @Test
    void exposesAllFourRuleConditionSpecsAndExecutesEachRuleTypeCleanly() {
        Map<String, Object> specs = engine.conditionsSpec();
        assertThat(specs).containsKeys(
            "long_term_uptrend",
            "percentage_change",
            "moving_average",
            "price_level"
        );

        List<OhlcBar> bars = buildOscillatingTrendBars(LocalDate.of(2023, 1, 2), 260);

        // 1. Long-term uptrend with take-profit / stop-loss
        Map<String, Object> uptrendRes = engine.runRuleBacktest(bars, Map.of(
            "pair", "eur/usd",
            "initial_capital", 10_000,
            "allow_multiple_trades", true,
            "entry", Map.of("type", "long_term_uptrend", "trend_period", 20, "min_return_pct", 1.0, "enable_trend_consistency", true),
            "exit", Map.of("max_holding_days", 25, "stop_loss_pct", 2.0, "take_profit_pct", 3.0)
        ));
        assertThat(uptrendRes).containsEntry("success", true).containsEntry("pair", "EUR/USD");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary1 = (Map<String, Object>) uptrendRes.get("summary");
        assertThat((Integer) summary1.get("num_trades")).isPositive();

        // 2. Percentage change with exit signal & single-trade limit
        Map<String, Object> pctRes = engine.runRuleBacktest(bars, Map.of(
            "pair", "GBP/USD",
            "initial_capital", "15000",
            "allow_multiple_trades", false,
            "entry", Map.of("type", "percentage_change", "custom_period", "10", "custom_threshold", "1.2"),
            "exit", Map.of(
                "max_holding_days", 90,
                "stop_loss_pct", 25.0,
                "take_profit_pct", 40.0,
                "signal", Map.of("type", "percentage_change", "custom_period", 5, "custom_threshold", 0.8)
            )
        ));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> singleTrades = (List<Map<String, Object>>) pctRes.get("trades");
        assertThat(singleTrades).hasSize(1);

        // 3. Moving average crossover alias + exit by death_cross
        Map<String, Object> maRes = engine.runRuleBacktest(bars, Map.of(
            "pair", "GOLD/USD",
            "initial_capital", 20_000,
            "allow_multiple_trades", true,
            "entry", Map.of("type", "moving_average_crossover", "ma_short_period", 8, "ma_long_period", 21, "signal_type", "golden_cross"),
            "exit", Map.of(
                "max_holding_days", 15,
                "stop_loss_pct", 0.0,
                "take_profit_pct", 0.0,
                "signal", Map.of("type", "moving_average", "ma_short_period", 8, "ma_long_period", 21, "signal_type", "death_cross")
            )
        ));
        assertThat(maRes).containsEntry("success", true);

        // 4. Price level breakout + outside_range + end_of_window fallback
        Map<String, Object> priceRes = engine.runRuleBacktest(bars, Map.of(
            "pair", "USD/JPY",
            "initial_capital", 10_000,
            "entry", Map.of("type", "price_threshold", "price_high", 1.09, "price_low", 1.02, "trigger_type", "outside_range"),
            "exit", Map.of("max_holding_days", 500, "stop_loss_pct", 0, "take_profit_pct", 0)
        ));
        assertThat(priceRes).containsEntry("success", true);
    }

    @Test
    void rejectsMalformedPayloadsAndShortHistories() {
        List<OhlcBar> bars = buildOscillatingTrendBars(LocalDate.of(2024, 1, 2), 60);

        assertThatThrownBy(() -> engine.runRuleBacktest(bars, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.runRuleBacktest(bars, Map.of("pair", "  ")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.runRuleBacktest(bars, Map.of("pair", "EUR/USD", "initial_capital", -5)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.runRuleBacktest(bars, Map.of("pair", "EUR/USD", "entry", "bad")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.runRuleBacktest(bars, Map.of("pair", "EUR/USD", "entry", Map.of("type", "magic_indicator"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.runRuleBacktest(bars.subList(0, 10), Map.of(
            "pair", "EUR/USD",
            "entry", Map.of("type", "percentage_change")
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<OhlcBar> buildOscillatingTrendBars(LocalDate start, int count) {
        List<OhlcBar> out = new ArrayList<>(count);
        LocalDate d = start;
        double prevClose = 1.0500;
        int added = 0;
        while (added < count) {
            if (isWeekday(d)) {
                double open = prevClose;
                double close = 1.0500 + (added * 0.0006) + Math.sin(added * 0.28) * 0.035;
                double high = Math.max(open, close) * 1.012;
                double low = Math.min(open, close) * 0.988;
                out.add(new OhlcBar(d.toString(), open, high, low, close, 1000.0));
                prevClose = close;
                added++;
            }
            d = d.plusDays(1);
        }
        return out;
    }

    private static boolean isWeekday(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> false;
            default -> true;
        };
    }
}
