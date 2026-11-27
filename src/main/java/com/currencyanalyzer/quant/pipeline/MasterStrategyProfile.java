package com.currencyanalyzer.quant.pipeline;

/**
 * Registered Master Arena configuration pairing a Stage 2 signal engine with a Stage 3 overlay.
 */
public record MasterStrategyProfile(
    String id,
    String displayName,
    SignalEngine signalEngine,
    RegimeRiskOverlay riskOverlay
) {
}
