package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;
import java.util.Map;

/**
 * Stage 3 regime risk overlay modulating target portfolio volatility during stress regimes.
 */
public interface RegimeRiskOverlay {

    String id();

    double riskMultiplier(LocalDate date, PortfolioState state, Map<String, Double> pairAnnualizedVols);
}
