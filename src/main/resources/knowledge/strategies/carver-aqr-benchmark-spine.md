# Strategy Card: Carver / AQR Benchmark Spine (Trend + Carry + Vol-Targeting)

- **Status:** GRADUATED
- **Pipeline Slot:** Stage 2 (`CarverAqrSignalEngine`) + Stage 3 (`VolatilityTargetRiskSizer`)
- **Primary Sources:** Rob Carver (*Systematic Trading*), Moskowitz-Ooi-Pedersen (2012) Time-Series Momentum, Koijen et al. (2018) Carry
- **Free Parameters:** `70/30` Trend-Carry blend, `1.25` Forecast Diversification Multiplier, `10%` rebalance inertia buffer

## Exact Mathematical Rule
1. **Stage 2 Signal (`[-1.0, +1.0]` after `252`-bar warm-up):**
   - Trend component = average of volatility-normalized `60d`, `120d`, `252d` TSMOM returns and `EMA(16)/EMA(64)` EWMAC.
   - Carry component = `(r_base - r_quote) / trailing_63d_annualized_vol`, clamped to `[-1.0, +1.0]`.
   - `Signal = clamp((0.70 * Trend + 0.30 * Carry) * 1.25, -1.0, +1.0)`.
2. **Stage 3 Sizing & Safeguards:**
   - `TargetNotional = Signal * (Equity * TargetVolPct / sqrt(N)) / max(2%, PairAnnualizedVol)`.
   - Enforce net currency cluster cap (`1.5x` equity) and skip same-side rebalances inside the `10%` inertia buffer.
3. **Stage 4–5 Execution & Attribution:**
   - Day `t` signal fills at Day `t+1` Open (`2`-pip G10 spread/slippage, `3`-pip on `GOLD/USD`), accrues daily carry net of `1%` broker markup (`3x` Wednesday swap), and separates `Spot PnL`, `Carry PnL`, and `Friction Drag` in `USD`.

## Verdict & Known Regime Vulnerability
Serves as the conflict-free **Benchmark Spine** for G10 FX and `GOLD/USD` / `SILVER/USD`. Captures durable macro divergence and positive policy-rate carry while suppressing spread churn during sideways chop via the `10%` inertia buffer. Known vulnerability is rapid crowded-carry liquidation (e.g., Aug 2024 `JPY` unwind), which is explicitly stress-tested against Stage 3 `DrawdownAndVolSpikeOverlay`.
