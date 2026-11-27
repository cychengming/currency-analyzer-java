package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Stage 3 Master Arena overlay inspired by Paul Tudor Jones / Ed Thorp / Howard Marks:
 * throttles portfolio target volatility during deep portfolio drawdowns or top-decile FX vol spikes.
 */
public class DrawdownAndVolSpikeOverlay implements RegimeRiskOverlay {

    public static final String OVERLAY_ID = "drawdown-vol-throttle";

    private final double softDrawdownPct;
    private final double hardDrawdownPct;
    private final double highVolThreshold;
    private final double minMultiplier;

    public DrawdownAndVolSpikeOverlay() {
        this(8.0, 20.0, 0.16, 0.40);
    }

    public DrawdownAndVolSpikeOverlay(
        double softDrawdownPct,
        double hardDrawdownPct,
        double highVolThreshold,
        double minMultiplier
    ) {
        this.softDrawdownPct = Math.max(1.0, softDrawdownPct);
        this.hardDrawdownPct = Math.max(this.softDrawdownPct + 1.0, hardDrawdownPct);
        this.highVolThreshold = Math.max(0.05, highVolThreshold);
        this.minMultiplier = Math.max(0.10, Math.min(1.0, minMultiplier));
    }

    @Override
    public String id() {
        return OVERLAY_ID;
    }

    @Override
    public double riskMultiplier(LocalDate date, PortfolioState state, Map<String, Double> pairAnnualizedVols) {
        double ddAbs = state == null ? 0.0 : Math.abs(Math.min(0.0, state.drawdownPct()));
        double ddMult = 1.0;
        if (ddAbs > softDrawdownPct) {
            double progress = Math.min(1.0, (ddAbs - softDrawdownPct) / (hardDrawdownPct - softDrawdownPct));
            ddMult = 1.0 - progress * (1.0 - minMultiplier);
        }

        double volMult = 1.0;
        double medianVol = medianVol(pairAnnualizedVols);
        if (medianVol > highVolThreshold) {
            volMult = Math.max(minMultiplier, highVolThreshold / medianVol);
        }

        return Math.max(minMultiplier, Math.min(ddMult, volMult));
    }

    private static double medianVol(Map<String, Double> vols) {
        if (vols == null || vols.isEmpty()) {
            return 0.0;
        }
        List<Double> values = new ArrayList<>();
        for (Double v : vols.values()) {
            if (v != null && Double.isFinite(v) && v > 0) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return 0.0;
        }
        Collections.sort(values);
        int mid = values.size() / 2;
        if (values.size() % 2 == 1) {
            return values.get(mid);
        }
        return 0.5 * (values.get(mid - 1) + values.get(mid));
    }
}
