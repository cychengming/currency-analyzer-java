# Scenario Runbook: Risk Limits, Drawdown Emergencies & Correlation Clusters

## Trigger Conditions
- A proposed trade reports `exceeds_daily_risk_limit = true` against `daily_risk_limit_pct`.
- Portfolio equity enters a `>= 8%` drawdown or you experience `3` or more consecutive losing trades.
- Multiple open positions share heavy directional `USD` or `JPY` exposure (e.g., Long `EUR/USD`, Long `GBP/USD`, Long `GOLD/USD`, Short `USD/JPY`).

## Diagnostic Tools to Call First
1. `get_settings` — confirm the active `daily_risk_limit_pct`.
2. `calculate_position_size` — compute exact `risk_amount`, `position_units`, `implied_leverage`, and `exceeds_daily_risk_limit`.
3. `compare_pairs` — measure 90-day Pearson correlation and `beta_first_vs_second` between open pairs and the candidate trade.

## Action Protocol
1. **Daily Risk Limit Hit (`exceeds_daily_risk_limit = true`):** Stop adding risk for the day. Either reduce stop-adjusted position units so total open + planned risk stays within `daily_risk_limit_pct`, or wait until the next NY 5:00 PM session.
2. **Drawdown Heat Throttle (`-8%` to `-20%` or 3-loss streak):** Cut per-trade risk budget by `50%` (matching the Stage 3 `DrawdownAndVolSpikeOverlay` step-down down to `0.40x`) until two disciplined wins or equity recovery above the 20-day moving average of equity.
3. **USD Cluster Concentration (`pearson_correlation > 0.65`):** Treat correlated pairs as one combined macro bet. Keep aggregate single-currency net exposure within `1.5x` account equity.

## Hard Red Lines
- Never raise `daily_risk_limit_pct` mid-day or mid-drawdown to force an extra trade through.
- Never place two `> 0.70` correlated trades at full `1%` risk each while treating them as independent bets.
