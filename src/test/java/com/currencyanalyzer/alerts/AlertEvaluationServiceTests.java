package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertEvaluationService.EvaluationSummary;
import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertPreference;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.pubsub.AlertEventPublisher;
import com.currencyanalyzer.pubsub.AlertTriggeredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceTests {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    @Mock
    private AlertMonitoringRepository repository;
    @Mock
    private AlertMonitoringService monitoringService;
    @Mock
    private MarketDataService marketDataService;
    @Mock
    private AlertEventPublisher eventPublisher;

    private AlertEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new AlertEvaluationService(
            repository,
            monitoringService,
            marketDataService,
            eventPublisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            3600
        );
    }

    @Test
    void skipsEvaluationWhenMonitoringOrAlertsDisabled() {
        when(monitoringService.isMonitoringActive()).thenReturn(false);
        EvaluationSummary disabledMonitoring = service.evaluateTick();
        assertThat(disabledMonitoring.status()).isEqualTo("skipped");
        assertThat(disabledMonitoring.reason()).isEqualTo("monitoring_disabled");

        when(monitoringService.isMonitoringActive()).thenReturn(true);
        when(monitoringService.getSettings()).thenReturn(Map.of("enable_alerts", false));
        EvaluationSummary disabledAlerts = service.evaluateTick();
        assertThat(disabledAlerts.status()).isEqualTo("skipped");
        assertThat(disabledAlerts.reason()).isEqualTo("alerts_disabled");
    }

    @Test
    void evaluatesPercentageChangePriceThresholdAndMovingAverageCrossoverRules() {
        when(monitoringService.isMonitoringActive()).thenReturn(true);
        when(monitoringService.getSettings()).thenReturn(Map.of(
            "enable_alerts", true,
            "trend_threshold", 2.0,
            "detection_period", 30
        ));

        AlertPreference ndxPct = new AlertPreference(
            "NDX/USD", true, "percentage_change", 3.0, 1, false, 5, null, null, "outside_range", null, 10, 50, "both"
        );
        AlertPreference eurThreshold = new AlertPreference(
            "EUR/USD", true, "price_threshold", 2.0, 30, true, 5, 1.15, 1.05, "outside_range", null, 10, 50, "both"
        );
        AlertPreference goldMa = new AlertPreference(
            "GOLD/USD", true, "moving_average_crossover", 2.0, 30, true, 5, null, null, "outside_range", null, 2, 4, "golden_cross"
        );
        AlertPreference disabledPair = new AlertPreference(
            "GBP/USD", false, "price_threshold", 2.0, 30, true, 5, 1.0, 0.5, "above", null, 10, 50, "both"
        );
        AlertPreference whipsawPair = new AlertPreference(
            "USD/JPY", true, "percentage_change", 2.0, 5, true, 5, null, null, "outside_range", null, 10, 50, "both"
        );

        when(monitoringService.getEffectivePreferences()).thenReturn(
            List.of(ndxPct, eurThreshold, goldMa, disabledPair, whipsawPair)
        );
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "NDX/USD", new LiveRate(26746.10, 1717.12, 6.86),
            "EUR/USD", new LiveRate(1.04, -0.02, -1.89),
            "GOLD/USD", new LiveRate(120.0, 20.0, 20.0),
            "GBP/USD", new LiveRate(1.30, 0.01, 0.77),
            "USD/JPY", new LiveRate(155.0, 5.0, 3.33)
        ));
        when(marketDataService.fetchHistorical("GOLD/USD", 9)).thenReturn(List.of(
            new HistoricalPoint("2026-11-19", 105.0),
            new HistoricalPoint("2026-11-20", 104.0),
            new HistoricalPoint("2026-11-21", 100.0),
            new HistoricalPoint("2026-11-22", 100.0)
        ));
        // Alternating whipsaw series fails directional trend consistency (1 up, 3 down while live jumps up)
        when(marketDataService.fetchHistorical("USD/JPY", 5)).thenReturn(List.of(
            new HistoricalPoint("2026-11-18", 150.0),
            new HistoricalPoint("2026-11-19", 149.0),
            new HistoricalPoint("2026-11-20", 148.0),
            new HistoricalPoint("2026-11-21", 147.0),
            new HistoricalPoint("2026-11-22", 148.0)
        ));

        when(repository.claimCooldownWindow(eq("NDX/USD"), eq(NOW), eq(NOW.minusSeconds(3600)))).thenReturn(true);
        when(repository.claimCooldownWindow(eq("EUR/USD"), eq(NOW), eq(NOW.minusSeconds(3600)))).thenReturn(true);
        when(repository.claimCooldownWindow(eq("GOLD/USD"), eq(NOW), eq(NOW.minusSeconds(3600)))).thenReturn(false);

        AlertEntry ndxAlert = new AlertEntry(10L, "NDX/USD", 6.86, 25028.98, 26746.10, NOW.toString(), false, "percentage_change", 6.86, 3.0);
        AlertEntry eurAlert = new AlertEntry(11L, "EUR/USD", -0.95, 1.05, 1.04, NOW.toString(), false, "price_threshold", 1.04, 1.05);
        when(repository.insertAlert(eq("NDX/USD"), anyDouble(), anyDouble(), anyDouble(), eq(NOW), eq("percentage_change"), anyDouble(), anyDouble()))
            .thenReturn(ndxAlert);
        when(repository.insertAlert(eq("EUR/USD"), anyDouble(), anyDouble(), anyDouble(), eq(NOW), eq("price_threshold"), anyDouble(), anyDouble()))
            .thenReturn(eurAlert);

        doThrow(new AlertDeliveryException("transient pubsub outage", null))
            .when(eventPublisher).publish(AlertTriggeredEvent.from(eurAlert));

        EvaluationSummary summary = service.evaluateTick();

        assertThat(summary.status()).isEqualTo("ok");
        assertThat(summary.evaluated()).isEqualTo(4);
        assertThat(summary.triggered()).isEqualTo(2);
        assertThat(summary.alerts()).containsExactly(ndxAlert, eurAlert);

        verify(eventPublisher).publish(AlertTriggeredEvent.from(ndxAlert));
        verify(eventPublisher).publish(AlertTriggeredEvent.from(eurAlert));
        verify(repository, never()).insertAlert(eq("GOLD/USD"), anyDouble(), anyDouble(), anyDouble(), any(), anyString(), anyDouble(), anyDouble());
        verify(repository, never()).insertAlert(eq("USD/JPY"), anyDouble(), anyDouble(), anyDouble(), any(), anyString(), anyDouble(), anyDouble());
    }

    @Test
    void coversAboveThresholdDeathCrossConsistentTrendAndNoMatchPaths() {
        when(monitoringService.isMonitoringActive()).thenReturn(true);
        when(monitoringService.getSettings()).thenReturn(Map.of(
            "enable_alerts", true,
            "trend_threshold", 2.0,
            "detection_period", 5
        ));

        AlertPreference spxAbove = new AlertPreference(
            "SPX/USD", true, "price_threshold", null, null, true, 5, 6000.0, 5000.0, "above", null, 10, 50, "both"
        );
        AlertPreference silverDeathCross = new AlertPreference(
            "SILVER/USD", true, "moving_average_crossover", null, null, true, 5, null, null, null, null, 2, 4, "death_cross"
        );
        AlertPreference cadConsistent = new AlertPreference(
            "USD/CAD", true, "percentage_change", null, null, true, 5, null, null, null, null, 10, 50, "both"
        );
        AlertPreference shortHistory = new AlertPreference(
            "AUD/USD", true, "moving_average_crossover", null, null, true, 5, null, null, null, null, 2, 4, "both"
        );
        AlertPreference belowThreshold = new AlertPreference(
            "NZD/USD", true, "percentage_change", 10.0, 1, false, 5, null, null, null, null, 10, 50, "both"
        );
        AlertPreference unknownRule = new AlertPreference(
            "USD/CHF", true, "unsupported_rule", 2.0, 1, false, 5, null, null, null, null, 10, 50, "both"
        );

        when(monitoringService.getEffectivePreferences()).thenReturn(List.of(
            spxAbove, silverDeathCross, cadConsistent, shortHistory, belowThreshold, unknownRule
        ));
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "SPX/USD", new LiveRate(6050.0, 50.0, 0.83),
            "SILVER/USD", new LiveRate(20.0, -10.0, -33.33),
            "USD/CAD", new LiveRate(1.40, 0.02, 1.45),
            "AUD/USD", new LiveRate(0.65, 0.01, 1.5),
            "NZD/USD", new LiveRate(0.60, 0.005, 0.84),
            "USD/CHF", new LiveRate(0.88, 0.01, 1.15)
        ));
        // Death cross: prevShort=(31+31)/2=31 > prevLong=(29+30+31+31)/4=30.25; currShort=(31+20)/2=25.5 < currLong=(30+31+31+20)/4=28
        when(marketDataService.fetchHistorical("SILVER/USD", 9)).thenReturn(List.of(
            new HistoricalPoint("2026-11-19", 29.0),
            new HistoricalPoint("2026-11-20", 30.0),
            new HistoricalPoint("2026-11-21", 31.0),
            new HistoricalPoint("2026-11-22", 31.0)
        ));
        when(marketDataService.fetchHistorical("USD/CAD", 5)).thenReturn(List.of(
            new HistoricalPoint("2026-11-19", 1.30),
            new HistoricalPoint("2026-11-20", 1.32),
            new HistoricalPoint("2026-11-21", 1.35),
            new HistoricalPoint("2026-11-22", 1.38)
        ));
        when(marketDataService.fetchHistorical("AUD/USD", 9)).thenReturn(List.of(
            new HistoricalPoint("2026-11-22", 0.64)
        ));

        when(repository.claimCooldownWindow(anyString(), eq(NOW), eq(NOW.minusSeconds(3600)))).thenReturn(true);
        when(repository.insertAlert(anyString(), anyDouble(), anyDouble(), anyDouble(), eq(NOW), anyString(), anyDouble(), anyDouble()))
            .thenAnswer(inv -> new AlertEntry(
                100L,
                inv.getArgument(0),
                inv.getArgument(1),
                inv.getArgument(2),
                inv.getArgument(3),
                NOW.toString(),
                false,
                inv.getArgument(5),
                inv.getArgument(6),
                inv.getArgument(7)
            ));

        EvaluationSummary summary = service.evaluateTick();
        assertThat(summary.evaluated()).isEqualTo(6);
        assertThat(summary.triggered()).isEqualTo(3);
        assertThat(summary.alerts()).extracting(AlertEntry::pair)
            .containsExactly("SPX/USD", "SILVER/USD", "USD/CAD");
    }
}
