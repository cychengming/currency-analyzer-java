package com.currencyanalyzer.quant.pipeline;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 3 volatility-targeting portfolio risk sizer with net currency cluster caps,
 * {@link RegimeRiskOverlay} modulation, and Carver's rebalance inertia buffer.
 */
public class VolatilityTargetRiskSizer {

    private static final double MIN_ANNUALIZED_VOL = 0.02;

    /**
     * Computes target positions across all active pairs using point-in-time volatilities
     * and feedback {@link PortfolioState} from Stage 5.
     */
    public Map<String, TargetPosition> sizeTargets(
        LocalDate signalDate,
        Map<String, Double> signalsByPair,
        Map<String, FxDailyBar> barsByPair,
        Map<String, Double> pairAnnualizedVols,
        PortfolioState portfolioState,
        PipelineConfig config,
        RegimeRiskOverlay overlay
    ) {
        if (signalsByPair == null || signalsByPair.isEmpty()) {
            return Map.of();
        }
        int pairCount = Math.max(1, signalsByPair.size());
        double diversificationScale = Math.sqrt(pairCount);
        double overlayMult = overlay == null
            ? 1.0
            : Math.max(0.0, Math.min(1.0, overlay.riskMultiplier(signalDate, portfolioState, pairAnnualizedVols)));
        double effectiveTargetVol = config.targetVolatilityPct() * overlayMult;

        Map<String, Double> rawNotionals = new LinkedHashMap<>();
        Map<String, Double> safeVols = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : signalsByPair.entrySet()) {
            String pair = entry.getKey();
            double signal = Math.max(-1.0, Math.min(1.0, entry.getValue()));
            double pairVol = Math.max(
                MIN_ANNUALIZED_VOL,
                pairAnnualizedVols.getOrDefault(pair, 0.10)
            );
            safeVols.put(pair, pairVol);
            double rawNotionalUsd = signal * (portfolioState.equityUsd() * effectiveTargetVol / diversificationScale)
                / pairVol;
            rawNotionals.put(pair, rawNotionalUsd);
        }

        double maxClusterUsd = portfolioState.equityUsd() * config.maxCurrencyExposureMultiple();
        double clusterScale = computeClusterScale(rawNotionals, maxClusterUsd);
        boolean cappedByCluster = clusterScale < 0.999999;

        Map<String, TargetPosition> targets = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : signalsByPair.entrySet()) {
            String pair = entry.getKey();
            FxDailyBar bar = barsByPair.get(pair);
            if (bar == null || bar.close() <= 0) {
                continue;
            }
            InstrumentSpec spec = InstrumentCatalog.forPair(pair);
            double quoteToUsd = PortfolioLedger.resolveQuoteToUsd(spec, bar.close(), barsByPair);
            double usdPerBaseUnit = bar.close() * quoteToUsd;
            if (!Double.isFinite(usdPerBaseUnit) || usdPerBaseUnit <= 0) {
                continue;
            }

            double scaledNotionalUsd = rawNotionals.get(pair) * clusterScale;
            double desiredBaseUnits = scaledNotionalUsd / usdPerBaseUnit;

            OpenPosition existing = portfolioState.positions().get(pair);
            double currentBaseUnits = existing == null ? 0.0 : existing.baseUnits();

            boolean heldByInertia = false;
            double finalBaseUnits = desiredBaseUnits;
            double finalNotionalUsd = scaledNotionalUsd;

            if (shouldHoldForInertia(currentBaseUnits, desiredBaseUnits, config.rebalanceInertiaPct())) {
                finalBaseUnits = currentBaseUnits;
                finalNotionalUsd = currentBaseUnits * usdPerBaseUnit;
                heldByInertia = true;
            }

            targets.put(pair, new TargetPosition(
                pair,
                entry.getValue(),
                safeVols.get(pair) * 100.0,
                finalNotionalUsd,
                finalBaseUnits,
                cappedByCluster,
                heldByInertia
            ));
        }
        return Map.copyOf(targets);
    }

    private static double computeClusterScale(Map<String, Double> rawNotionals, double maxClusterUsd) {
        if (maxClusterUsd <= 0) {
            return 1.0;
        }
        Map<String, Double> netCurrency = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : rawNotionals.entrySet()) {
            double notional = entry.getValue();
            if (Math.abs(notional) < 1e-9) {
                continue;
            }
            InstrumentSpec spec = InstrumentCatalog.forPair(entry.getKey());
            netCurrency.merge(spec.baseCurrency(), notional, Double::sum);
            netCurrency.merge(spec.quoteCurrency(), -notional, Double::sum);
        }
        double peakAbsExposure = 0.0;
        for (double exposure : netCurrency.values()) {
            peakAbsExposure = Math.max(peakAbsExposure, Math.abs(exposure));
        }
        if (peakAbsExposure <= maxClusterUsd || peakAbsExposure <= 1e-9) {
            return 1.0;
        }
        return maxClusterUsd / peakAbsExposure;
    }

    private static boolean shouldHoldForInertia(
        double currentUnits,
        double desiredUnits,
        double inertiaPct
    ) {
        if (inertiaPct <= 0.0) {
            return false;
        }
        if (Math.abs(currentUnits) < 1e-9 || Math.abs(desiredUnits) < 1e-9) {
            return false;
        }
        if (Math.signum(currentUnits) != Math.signum(desiredUnits)) {
            return false;
        }
        double denom = Math.max(Math.abs(currentUnits), Math.abs(desiredUnits));
        return Math.abs(desiredUnits - currentUnits) <= inertiaPct * denom;
    }
}
