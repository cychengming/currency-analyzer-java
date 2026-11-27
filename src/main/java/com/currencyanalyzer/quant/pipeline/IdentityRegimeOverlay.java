package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;
import java.util.Map;

/**
 * Default pass-through Stage 3 risk overlay (1.0x target volatility).
 */
public class IdentityRegimeOverlay implements RegimeRiskOverlay {

    public static final String OVERLAY_ID = "identity";

    @Override
    public String id() {
        return OVERLAY_ID;
    }

    @Override
    public double riskMultiplier(LocalDate date, PortfolioState state, Map<String, Double> pairAnnualizedVols) {
        return 1.0;
    }
}
