# Scenario Runbook: Macro Shocks, Event Risk & Volatility Spikes

## Trigger Conditions
- Upcoming Tier-1 scheduled event within 24 hours (`FOMC`, `ECB`, `BoJ`, `US CPI`, `NFP`).
- Realized range or daily move exceeds `2x ATR(14)`, weekend gap exceeds `1%`, or a crowded carry pair (`USD/JPY`, `AUD/JPY`) experiences sudden unwind liquidation.

## Diagnostic Tools to Call First
1. `get_live_rates` — check live spot quotes and current daily % moves.
2. `analyze_pair_technicals` — read `annualized_volatility_pct`, `bollinger_bands_20.bandwidth_pct`, `rsi_14`, and `max_drawdown_pct`.
3. `compare_pairs` — verify whether cross-pair correlation has spiked toward `+0.80` / `-0.80` across USD or metal legs.
4. `list_my_alert_rules` — inspect active armed price or volatility alerts on affected pairs.

## Action Protocol
1. **Within 24h of Tier-1 Releases:** Avoid opening new full-size (`1.0%` equity) positions in directly exposed USD or JPY pairs unless the setup already has a pre-defined hard invalidation outside typical event noise (`>= 1.5x ATR(14)`).
2. **During Volatility Spikes (`annualized_volatility_pct > 18%`):** Follow the Stage 3 `DrawdownAndVolSpikeOverlay` principle—scale position sizing proportionally down (`target_vol / realized_vol`, floored at `0.40x`) rather than widening risk dollars.
3. **Weekend / Session Gaps:** Never widen an existing stop loss after a gap against you; exit or reduce immediately to bring remaining risk back inside `daily_risk_limit_pct`.
4. **Carry Unwind Screening:** When high-carry pairs break below `SMA-20` and `SMA-50` simultaneously with rising volatility, cut carry exposure rather than averaging into the interest-rate differential.

## Hard Red Lines
- Never double down or average into a losing carry trade during a central-bank pivot or `> 2x ATR` shock.
- Never remove a stop loss before `FOMC`, `CPI`, `NFP`, or `BoJ` announcements.
