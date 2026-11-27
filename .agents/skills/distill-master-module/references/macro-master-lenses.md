# Curated FX/Macro & Risk Master Lenses

Use these distilled lenses during **Step 1 (Source Triangulation)** and **Step 2 (Contract Mapping)** of `distill-master-module`. Single-stock equity value/growth personas (Buffett, Munger, Lynch, Graham) are intentionally excluded because G10 FX and Spot Gold/Silver (`GOLD/USD`, `SILVER/USD`) derive edge from policy-rate differentials, central-bank liquidity cycles, volatility regimes, and cross-pair correlation—not corporate moats or 10-K filings.

---

## 1. Regime-to-Master Decision Router

Instead of mixing contradictory master opinions on the same signal, route each market regime to a specific pipeline stage:

| Market Regime | Observable Diagnostic (Point-in-Time) | Primary Master Lens | Target Pipeline Slot |
|---|---|---|---|
| **Baseline All-Weather Trend + Carry** | Default multi-pair daily operation after 252-bar warm-up | **Rob Carver / AQR** | Stage 2 + Stage 3 (`CarverAqrBenchmarkSpine`) |
| **Central-Bank Policy Divergence** | Rising `63d` policy-rate differential impulse aligned with `SMA-200` | **Stanley Druckenmiller** | Stage 2 Candidate (`SignalEngine`) |
| **Range Expansion / Breakout** | Multi-month consolidation resolving past 20/55-day Donchian channel | **Richard Dennis / Jerry Parker (Turtles)** | Stage 2 Candidate (`SignalEngine`) |
| **Crowded Carry / Policy Imbalance** | Wide carry differential paired with deteriorating rate support or peg stress | **George Soros** | Stage 2/3 Stress Hypothesis & Unwind Guard |
| **High-Correlation Macro Risk** | Multiple USD or JPY pairs moving in lockstep | **Ray Dalio** | Stage 3 (`VolatilityTargetRiskSizer` inverse-vol + cluster cap) |
| **Volatility Spike / Drawdown Heat** | Portfolio drawdown or cross-sectional pair vol entering top decile | **Howard Marks & Nassim Taleb** | Stage 3 Overlay (`RegimeRiskOverlay`) |

---

## 2. Four Distilled FX / Macro / Risk Lenses

### Stanley Druckenmiller — Policy Impulse & Trend Confirmation
- **Core Mechanism:** Exchange rates trend when central-bank liquidity and short-term rate expectations inflect faster than consensus prices in.
- **Pipeline Translation:** Compute trailing `63`-day change in policy-rate spread `Δ(r_base - r_quote)` and require confirmation from long-horizon price trend (`SMA-200`). Scale conviction up when rate impulse and price trend agree; dampen or stay flat when they diverge.
- **Failure Mode:** Sideways central-bank hold regimes where policy rates do not move for quarters while spot chops inside a range.

### George Soros — Reflexivity & Crowded Carry Unwinds
- **Core Mechanism:** Persistent carry differentials attract leveraged positioning, suppressing volatility until a policy pivot triggers forced liquidation (reflexive feedback loop, e.g., ERM 1992 or Aug 2024 `JPY` unwind).
- **Pipeline Translation:** Hypothesis for Stage 3 crowding/unwind protection: when high carry coincides with rising realized volatility and adverse momentum, cut carry exposure rather than averaging into the carry trade.
- **Failure Mode:** Early exit during slow, orderly carry trends before any volatility break occurs.

### Ray Dalio — Inverse-Volatility & Currency-Cluster Budgeting
- **Core Mechanism:** Equal-dollar allocations create hidden concentration because high-volatility instruments (`GOLD/USD`, `GBP/JPY`) and shared quote/base legs (`USD` across 7 pairs) dominate portfolio variance.
- **Pipeline Translation:** Size every position inversely to trailing annualized volatility (`TargetNotional = signal * (Equity * TargetVol) / PairVol`) and enforce net currency cluster exposure caps across all open FX and metal legs.
- **Failure Mode:** Sudden correlation spikes to `1.0` during global liquidity squeezes (`2008 GFC`), requiring an additional Stage 3 drawdown/vol overlay.

### Howard Marks & Nassim Taleb — Pendulum Risk Control & Tail Throttling
- **Core Mechanism:** Forecasting macro tops is unreliable, but measuring where volatility and portfolio drawdown sit relative to stress thresholds is objective. Protecting capital in the left tail preserves compounding.
- **Pipeline Translation:** Stage 3 `RegimeRiskOverlay` linearly throttles `TargetVolPct` (e.g., down to `0.40x`) when portfolio drawdown deepens or median pair volatility spikes above stress thresholds.
- **Failure Mode:** Over-aggressive throttling can delay re-entry during V-shaped recoveries if the recovery threshold is too tight.

---

## 3. `LLMQuant` Rates & FX Output Contract (For Milestone 3 Skills)

When Milestone 3 In-App Skills (`/morning-brief`, `/pre-trade-check`) summarize Stage 1–5 numbers, always ground explanations on these exact deterministic fields:

- `pair` & `as_of_date` (NY 5:00 PM aligned close)
- `base_policy_rate_pct` & `quote_policy_rate_pct` (`r_base`, `r_quote`)
- `net_carry_bps` (after `100` bps / `1%` broker markup)
- `rate_impulse_63d_bps` (`Δ(r_base - r_quote)` over trailing 63 trading days)
- `annualized_pair_vol_pct` (trailing point-in-time log-return volatility)
- `signal` (`[-1.0, +1.0]`), `target_notional_usd`, `inertia_held` (`true` / `false`)
- `spot_pnl_usd`, `carry_pnl_usd`, `friction_cost_usd` (Stage 5 attribution)
