# Scenario Runbook: Alerts 2.0 Workflow & Spot-vs-Carry Attribution Review

## Trigger Conditions
- Setting up price, percentage-move, or moving-average alerts for an active watchlist or open trade.
- Reviewing whether a multi-week FX or Metal trade made money from true directional Spot movement or from interest-rate Carry rollover.

## Diagnostic Tools to Call First
1. `list_my_alert_rules` — review armed, triggered, and paused rules plus live distance `%`.
2. `get_my_alert_inbox` — check recent unread triggered alerts before creating duplicates.
3. `get_settings` — confirm monitoring cadence and notification preferences.

## Action Protocol
1. **Alert Rule Hygiene:** Use `ONCE` frequency for key breakout/invalidation levels (so an alert auto-disarms after firing) and `RECURRING` with `>= 1h` cooldown only for regime monitors (`ma_crossover` or `pct_move`).
2. **Decompose Net PnL Before Scaling:** Always inspect `Net PnL = Spot PnL + Carry PnL - Friction Drag`. If `Carry PnL` is positive while `Spot PnL` is flat or negative in a rising-volatility regime, treat the position as carry-driven rather than trend-confirmed.
3. **Check Wednesday 3x Swap & Broker Markup:** Factor `1%` annual financing markup and `3x` Wednesday rollover into multi-week holding cost before holding negative-carry positions (`short USD/JPY`, `long GOLD/USD` under high USD rates).

## Hard Red Lines
- Never leave duplicate `RECURRING` alerts armed at identical levels that cause alert fatigue.
- Never scale up a position assuming "trend strength" when attribution shows >100% of gains came from carry while spot is losing ground.
