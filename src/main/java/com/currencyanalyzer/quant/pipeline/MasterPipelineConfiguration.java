package com.currencyanalyzer.quant.pipeline;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single Spring configuration wiring the 5-stage pure-Java pipeline and Master Arena profiles
 * without competing {@code @Component} interface beans (Sonar {@code java:S9352}).
 */
@Configuration
public class MasterPipelineConfiguration {

    @Bean
    VolatilityTargetRiskSizer volatilityTargetRiskSizer() {
        return new VolatilityTargetRiskSizer();
    }

    @Bean
    ExecutionAndCarrySimulator executionAndCarrySimulator() {
        return new ExecutionAndCarrySimulator();
    }

    @Bean
    FxMasterPipelineRunner fxMasterPipelineRunner(
        VolatilityTargetRiskSizer riskSizer,
        ExecutionAndCarrySimulator executionSimulator
    ) {
        return new FxMasterPipelineRunner(riskSizer, executionSimulator);
    }

    @Bean
    Map<String, MasterStrategyProfile> masterStrategyProfiles() {
        CarverAqrSignalEngine carver = new CarverAqrSignalEngine();
        DonchianBreakoutSignalEngine donchian = new DonchianBreakoutSignalEngine();
        DruckenmillerRateImpulseSignalEngine druckenmiller = new DruckenmillerRateImpulseSignalEngine();

        IdentityRegimeOverlay identityOverlay = new IdentityRegimeOverlay();
        DrawdownAndVolSpikeOverlay guardedOverlay = new DrawdownAndVolSpikeOverlay();

        Map<String, MasterStrategyProfile> profiles = new LinkedHashMap<>();
        profiles.put(CarverAqrSignalEngine.ID, new MasterStrategyProfile(
            CarverAqrSignalEngine.ID,
            carver.displayName(),
            carver,
            identityOverlay
        ));
        profiles.put("carver-guarded-overlay", new MasterStrategyProfile(
            "carver-guarded-overlay",
            "Carver / AQR Spine + Drawdown/Vol Throttle",
            carver,
            guardedOverlay
        ));
        profiles.put(DonchianBreakoutSignalEngine.ID, new MasterStrategyProfile(
            DonchianBreakoutSignalEngine.ID,
            donchian.displayName(),
            donchian,
            identityOverlay
        ));
        profiles.put(DruckenmillerRateImpulseSignalEngine.ID, new MasterStrategyProfile(
            DruckenmillerRateImpulseSignalEngine.ID,
            druckenmiller.displayName(),
            druckenmiller,
            guardedOverlay
        ));
        return Map.copyOf(profiles);
    }
}
