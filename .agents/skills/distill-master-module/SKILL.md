---
name: distill-master-module
description: >-
  Distills a trading master's methodology beyond their book into a falsifiable
  Stage 2 (SignalEngine) or Stage 3 (PortfolioRiskSizer / RegimeRiskOverlay)
  Java module for the 5-stage FX backtesting pipeline, benchmarks Spot vs.
  Carry attribution against the Carver/AQR spine, and publishes a 1-page
  Vetted Strategy Card.
---

# Distill Trading Master Into a Falsifiable Pipeline Module

Use this workflow whenever translating a trader, fund, or paper (e.g., Carver/AQR, Turtles/Parker, Druckenmiller, Tudor Jones, Thorp, Marks) into `currency-analyzer-java`. Never dump raw book summaries or conflicting commentary into RAG—compile ideas into falsifiable Java pipeline stages and record only the measured outcome.

## Step 1: Source Triangulation (Beyond the Book)

Before writing any code, triangulate **three** independent evidence types so anecdotes and survivorship bias are stripped away:

1. **Primary Philosophy:** Books, shareholder letters, or direct interviews stating *why* the edge exists (risk premium, behavioral herding, central-bank inertia, liquidity shock). For FX and macro lenses, consult [macro-master-lenses.md](references/macro-master-lenses.md) (which filters out equity-only stock-picking personas).
2. **Empirical Factor / Academic Replication:** Peer-reviewed or practitioner papers quantifying the effect across decades and pairs (e.g., Moskowitz, Ooi & Pedersen 2012 for Time-Series Momentum; Koijen et al. 2018 for Carry; Carver's *Systematic Trading* for EWMAC + volatility targeting).
3. **Crisis Failure Modes:** Specific historical episodes where the approach suffered severe drawdowns or structural breaks (e.g., `2015–2019` FX low-vol chop for trend; `Aug 2024` JPY carry unwind for unhedged carry).

> **Rejection Gate:** If a book claim cannot be expressed as a causal, timestamp-aligned rule over daily OHLC bars, policy-rate differentials, or portfolio equity state, reject it here—do not add it as prose to RAG.

## Step 2: Strict 5-Stage Contract Mapping & Bias Audit

Map the distilled mechanism strictly to one slot in the 5-stage Java pipeline (`com.currencyanalyzer.quant.pipeline`):

- **Stage 2 (`SignalEngine`):** `double computeSignal(BarSeries series, PolicyRatePair rates, int index)` returning a normalized target weight in `[-1.0, +1.0]`.
- **Stage 3 (`PortfolioRiskSizer` or `RegimeRiskOverlay`):** Modulates target notional using `PortfolioState` (equity, pair volatility, cluster exposure, drawdown heat) and optional `10%` rebalance inertia buffering.
- **Stage 4 (`ExecutionGuard`):** Only for explicit rollover, event-window, or spread-cost rules—never alter Stage 4 to make a weak signal look profitable.

### Mandatory Bias & Overfitting Checklist

Verify all Java/`Ta4j` implementations against the `WRONG` vs. `CORRECT` patterns in [anti-leakage-java.md](references/anti-leakage-java.md):

- [ ] **No Same-Day Close Fills:** Signals computed from Day `t` close must execute strictly at Day `t+1` Open (Stage 4).
- [ ] **No Look-Ahead Normalization:** Volatility (`ATR`, EWMA, `252`d sigma), z-scores, and percentile ranks must use only bars `<= t`.
- [ ] **Warm-Up Guard:** Enforce a minimum `252`-bar warm-up period before emitting non-zero signals.
- [ ] **NY 5:00 PM Alignment:** Align all FX and `GOLD/USD` / `SILVER/USD` bars to 5:00 PM New York close so carry rollovers and cross-pair correlations match reality.
- [ ] **Parameter Parsimony:** Maximum `3` free parameters across all pairs (no per-pair curve-fitted thresholds or magic constants).

## Step 3: Master Arena Falsification & Crisis Replay Gate

Test the candidate module against the **Carver / AQR Benchmark Spine** using identical Stage 1 data (`G10 FX + GOLD/USD + SILVER/USD`), Stage 4 friction (`2`-pip base spread/slippage + `Daily Carry` with `1%` broker markup and `3x` Wednesday swap), and Stage 5 `USD` mark-to-market attribution.

### Required Stress & Walk-Forward Windows

Evaluate both out-of-sample rolling walk-forward splits and these four historical regimes:
1. **2008 Global Financial Crisis (`2008-07` to `2009-03`):** Rapid deleveraging and USD funding squeeze.
2. **2015–2019 FX Low-Vol Chop:** Whipsaw decay and spread/inertia drag testing.
3. **2022–2023 USD Hiking Cycle:** Strong trend + wide policy-rate carry dispersion.
4. **August 2024 JPY Carry Unwind (`2024-07` to `2024-09`):** Crowded carry crash and volatility spike.

### Required Attribution & Pass/Fail Bar

Every run must report full Stage 5 decomposition:
$$\text{Net PnL} = \text{Spot PnL} + \text{Carry PnL} - \text{Spread/Slippage Friction}$$

A candidate **Graduates** only if it satisfies **both**:
1. Improves out-of-sample risk-adjusted return (Net Sharpe / Calmar) **or** materially cuts crisis Max Drawdown / 95% VaR relative to the Carver/AQR Spine without sacrificing net expectancy.
2. Remains stable across `±20%` parameter perturbation (no cliff edges) and survives `2x` spread stress.

If it fails, **do not delete the finding**—record it as `FALSIFIED_NEGATIVE_RESULT` so you never waste time re-testing the same myth later.

## Step 4: Publish a 1-Page Vetted Strategy Card

Write a single concise Markdown card (under `knowledge/strategies/<slug>.md`, max ~1 page) using the template below. Only Vetted Strategy Cards enter Phase 1 RAG—never raw book chapters.

```markdown
# Strategy Card: <Master / Module Name>

- **Status:** GRADUATED | FALSIFIED_NEGATIVE_RESULT
- **Pipeline Slot:** Stage 2 (`SignalEngine`) | Stage 3 (`RegimeRiskOverlay`)
- **Primary Sources:** <Book/Interview> + <Academic Replication>
- **Free Parameters:** <e.g., fast=16, slow=64 (2 params)>

## Exact Mathematical Rule
<Concise formula matching the Java implementation>

## Master Arena Benchmark (vs. Carver/AQR Spine)
| Metric (Out-of-Sample + Stress) | Carver/AQR Spine | Candidate | Delta |
|---|---:|---:|---:|
| Net Sharpe (After Friction & Carry) | — | — | — |
| Spot PnL Share / Carry PnL Share | — | — | — |
| Max Drawdown (`%`) | — | — | — |
| Aug 2024 JPY Unwind Drawdown (`%`) | — | — | — |
| Annualized Turnover & Friction Drag (`%`) | — | — | — |

## Verdict & Known Regime Vulnerability
<1–2 paragraphs explaining when this module adds real edge, when it bleeds, and whether it is enabled in the live Master Target Sheet.>
```
