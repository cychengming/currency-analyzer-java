# Scenario Runbook: Discretionary Thesis vs. Master Spine Conflicts

## Trigger Conditions
- Your discretionary trade idea opposes the Stage 2 Carver/AQR Master Spine (`|signal| >= 0.50` in the opposite direction) or fights a strong negative policy-rate carry differential.
- You are tempted to rebalance or adjust an existing position for a small signal change inside the `10%` inertia buffer.

## Diagnostic Tools to Call First
1. `analyze_pair_technicals` — check `trend_regime` (`bullish_aligned`, `bearish_aligned`, `mixed`), `sma_20`, `sma_50`, and `rsi_14`.
2. `calculate_position_size` — verify exact `reward_to_risk_ratio` and `risk_pct` before overriding the systematic trend.
3. `search_notes` — check [Carver / AQR Benchmark Spine](carver-aqr-benchmark-spine.md) or related strategy cards for known regime edge and vulnerabilities.

## Action Protocol
1. **Opposing Strong Master Signal (`|signal| >= 0.50`):** Require a concrete catalyst (central-bank surprise, verified RSI divergence + key structural level) and a minimum `2.0 : 1` `reward_to_risk_ratio`. Cap counter-trend / counter-carry discretionary size to a `0.25%–0.50%` pilot risk slice.
2. **Respect the `10%` Inertia Buffer:** When `|target_units - current_units| <= 10%`, do **not** churn the position; spread and slippage friction will erode expectancy faster than the micro-adjustment adds alpha.
3. **Log Override Attribution:** Tag the trade entry reason explicitly as a Master Spine override so post-trade review can measure whether your discretionary override beat the shadow benchmark.

## Hard Red Lines
- Never run full leverage against both `SMA-20/50` trend alignment and policy-rate carry without a hard stop in place.
- Never execute sub-`10%` rebalance adjustments daily in low-volatility chop.
