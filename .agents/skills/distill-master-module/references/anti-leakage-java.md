# Anti-Leakage & Backtest Integrity Checklist (Java 25 / Ta4j)

Adapted from `ml4t/skills` `WRONG` vs. `CORRECT` quantitative validation patterns for `com.currencyanalyzer.quant.pipeline`. Every new Stage 2 (`SignalEngine`) or Stage 3 (`PortfolioRiskSizer` / `RegimeRiskOverlay`) module must pass all five checks before benchmarking.

---

## 1. Execution Timing: Never Fill on the Signal Bar's Close

- **WRONG:**
  ```java
  double signal = signalEngine.computeSignal(series, bars, t);
  double fillPrice = bars.get(t).close(); // Look-ahead! You cannot know Close(t) before Close(t)
  ```
- **CORRECT:**
  ```java
  // Signal computed from Day t close executes strictly at Day t+1 Open in Stage 4
  double signal = signalEngine.computeSignal(series, bars, t);
  OrderDelta delta = new OrderDelta(pair, bars.get(t).date(), prevUnits, targetUnits, targetUnits - prevUnits);
  FillReport fill = executionSimulator.simulateFillAndRollover(delta, bars.get(t + 1), quoteToUsd);
  ```

---

## 2. Volatility & Z-Score Normalization: Never Use Future Bars

- **WRONG:**
  ```java
  // Computing full-sample standard deviation leaks future volatility into earlier years
  DescriptiveStatistics allReturns = new DescriptiveStatistics(fullSeriesReturns);
  double fullPeriodVol = allReturns.getStandardDeviation() * Math.sqrt(252.0);
  ```
- **CORRECT:**
  ```java
  // Strictly slice trailing window [Math.max(1, index - lookback + 1) .. index]
  int start = Math.max(1, index - lookback + 1);
  DescriptiveStatistics trailing = new DescriptiveStatistics();
  for (int i = start; i <= index; i++) {
      trailing.addValue(Math.log(bars.get(i).close() / bars.get(i - 1).close()));
  }
  double annualizedVol = Math.max(0.02, trailing.getStandardDeviation() * Math.sqrt(252.0));
  ```

---

## 3. Point-in-Time Policy Rates & Session Alignment

- **WRONG:**
  ```java
  // Joining by exact date fails on holidays/rate holds, or querying latest rate leaks future hikes
  double rate = policyRateRepository.findLatestRate(currency);
  ```
- **CORRECT:**
  ```java
  // TreeMap.floorEntry(barDate) returns the most recent rate effective ON OR BEFORE barDate
  public double rateAsOf(String currency, LocalDate barDate) {
      Map.Entry<LocalDate, Double> entry = ratesByCurrency.get(currency).floorEntry(barDate);
      return entry != null ? entry.getValue() : 0.0;
  }
  ```

---

## 4. Carry & Friction Symmetry: Never Gift Markup to Short Positions

- **WRONG:**
  ```java
  // Multiplying signed notional by (rateDiff - markup) REVERSES the broker fee on short trades!
  double brokenCarry = signedNotionalUsd * ((baseRate - quoteRate - markup) / 100.0) / 365.0;
  ```
- **CORRECT:**
  ```java
  // Rate differential depends on position sign; broker financing markup ALWAYS costs |notional|
  double rawDiffCarry = signedNotionalUsd * ((baseRate - quoteRate) / 100.0);
  double brokerMarkupCost = Math.abs(signedNotionalUsd) * brokerCarryMarkupAnnual;
  double dailyCarryUsd = (rawDiffCarry - brokerMarkupCost) * (rolloverDays / 365.0);
  ```
  And in Stage 4 spot fills, `fillPrice` always worsens by `oneWayCostPips * pipSize` (`+cost` when buying, `-cost` when selling).

---

## 5. Warm-Up Guard, Parameter Parsimony & Perturbation Stability

- **WRONG:**
  - Tuning separate moving-average periods per pair (`EUR/USD = 11/43`, `USD/JPY = 19/71`).
  - Emitting full-size trades on bar `5` when `252`-day volatility is not yet populated.
- **CORRECT:**
  - Enforce `if (index < warmupBars()) return 0.0;` (`warmupBars() >= 252`).
  - Cap module hyperparameters at `<= 3` shared parameters across all G10 FX + `GOLD/USD` + `SILVER/USD`.
  - Verify `±20%` parameter perturbation and `2x` spread-stress tests in JUnit before graduating a Strategy Card.
