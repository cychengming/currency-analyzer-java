package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertEvaluationService.EvaluationSummary;
import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.alerts.AlertNotificationService.DeliveryResult;
import com.currencyanalyzer.alerts.UserAlertRuleRepository.AlertRule;
import com.currencyanalyzer.alerts.UserAlertRuleRepository.UserAlertEntry;
import com.currencyanalyzer.alerts.UserAlertRuleService.AlertRuleView;
import com.currencyanalyzer.alerts.UserAlertRuleService.ProfileRequest;
import com.currencyanalyzer.alerts.UserAlertRuleService.RuleRequest;
import com.currencyanalyzer.marketdata.HistoricalPoint;
import com.currencyanalyzer.marketdata.LiveRate;
import com.currencyanalyzer.marketdata.MarketDataService;
import com.currencyanalyzer.pubsub.AlertEventPublisher;
import com.currencyanalyzer.pubsub.AlertTriggeredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class UserAlertRulesEngineTests {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    @Mock
    private AlertMonitoringRepository legacyRepository;
    @Mock
    private UserAlertRuleRepository userRuleRepository;
    @Mock
    private AlertMonitoringService monitoringService;
    @Mock
    private MarketDataService marketDataService;
    @Mock
    private AlertEventPublisher eventPublisher;

    private AlertEvaluationService evaluationService;
    private UserAlertRuleService ruleService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        evaluationService = new AlertEvaluationService(
            legacyRepository,
            userRuleRepository,
            monitoringService,
            marketDataService,
            eventPublisher,
            clock,
            3600
        );
        ruleService = new UserAlertRuleService(userRuleRepository, marketDataService, clock);
    }

    @Test
    void evaluatesCrossingOnceRecurringPctAndMaRulesWithoutLegacySpam() {
        when(monitoringService.isMonitoringActive()).thenReturn(true);
        when(monitoringService.getSettings()).thenReturn(Map.of("enable_alerts", true));
        when(userRuleRepository.countAllRules()).thenReturn(5L);

        UUID crossUpId = UUID.randomUUID();
        UUID crossDownId = UUID.randomUUID();
        UUID noBaselineId = UUID.randomUUID();
        UUID pctRecurringId = UUID.randomUUID();
        UUID maCrossId = UUID.randomUUID();

        AlertRule crossUp = new AlertRule(
            crossUpId, 1L, "GOLD/USD", "Gold Breakout", UserAlertRuleRepository.COND_CROSS_ABOVE,
            3680.0, 30, 20, 50, "both", UserAlertRuleRepository.FREQ_ONCE, 3600, true,
            UserAlertRuleRepository.STATUS_ARMED, 3650.0, null, NOW.minusSeconds(600), NOW.minusSeconds(600)
        );
        AlertRule crossDown = new AlertRule(
            crossDownId, 2L, "EUR/USD", "EUR Floor", UserAlertRuleRepository.COND_CROSS_BELOW,
            1.0850, 30, 20, 50, "both", UserAlertRuleRepository.FREQ_ONCE, 3600, false,
            UserAlertRuleRepository.STATUS_ARMED, 1.0900, null, NOW.minusSeconds(600), NOW.minusSeconds(600)
        );
        // No previous baseline rate -> must NOT fire spuriously; only records baseline
        AlertRule noBaseline = new AlertRule(
            noBaselineId, 1L, "GOLD/USD", "Already Above", UserAlertRuleRepository.COND_CROSS_ABOVE,
            3500.0, 30, 20, 50, "both", UserAlertRuleRepository.FREQ_ONCE, 3600, true,
            UserAlertRuleRepository.STATUS_ARMED, null, null, NOW.minusSeconds(600), NOW.minusSeconds(600)
        );
        // Recurring % move in cooldown -> claimRecurringTrigger returns false
        AlertRule pctCooldown = new AlertRule(
            pctRecurringId, 2L, "NDX/USD", "NDX Swing", UserAlertRuleRepository.COND_PCT_MOVE,
            3.0, 1, 20, 50, "both", UserAlertRuleRepository.FREQ_RECURRING, 14400, true,
            UserAlertRuleRepository.STATUS_ARMED, 26000.0, NOW.minusSeconds(1200), NOW.minusSeconds(7200), NOW.minusSeconds(1200)
        );
        // MA golden cross -> fires and tolerates pubsub failure
        AlertRule maRule = new AlertRule(
            maCrossId, 1L, "SILVER/USD", "Silver Golden Cross", UserAlertRuleRepository.COND_MA_CROSSOVER,
            null, 30, 2, 4, "golden_cross", UserAlertRuleRepository.FREQ_RECURRING, 3600, true,
            UserAlertRuleRepository.STATUS_ARMED, 100.0, null, NOW.minusSeconds(600), NOW.minusSeconds(600)
        );

        when(userRuleRepository.findAllArmedRules()).thenReturn(List.of(
            crossUp, crossDown, noBaseline, pctCooldown, maRule
        ));
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "GOLD/USD", new LiveRate(3690.0, 40.0, 1.1),
            "EUR/USD", new LiveRate(1.0800, -0.01, -0.92),
            "NDX/USD", new LiveRate(27000.0, 1500.0, 5.88),
            "SILVER/USD", new LiveRate(120.0, 20.0, 20.0)
        ));
        when(marketDataService.fetchHistorical("SILVER/USD", 9)).thenReturn(List.of(
            new HistoricalPoint("2026-11-21", 105.0),
            new HistoricalPoint("2026-11-22", 104.0),
            new HistoricalPoint("2026-11-23", 100.0),
            new HistoricalPoint("2026-11-24", 100.0)
        ));

        when(userRuleRepository.claimOnceTrigger(crossUpId, 3690.0, NOW)).thenReturn(true);
        when(userRuleRepository.claimOnceTrigger(crossDownId, 1.0800, NOW)).thenReturn(true);
        when(userRuleRepository.claimRecurringTrigger(pctRecurringId, 27000.0, NOW, NOW.minusSeconds(14400))).thenReturn(false);
        when(userRuleRepository.claimRecurringTrigger(maCrossId, 120.0, NOW, NOW.minusSeconds(3600))).thenReturn(true);

        UserAlertEntry goldAlert = new UserAlertEntry(
            51L, 1L, crossUpId, "Gold Breakout", "GOLD/USD", 1.1, 3650.0, 3690.0,
            NOW.toString(), false, "cross_above", 3690.0, 3680.0, null
        );
        UserAlertEntry eurAlert = new UserAlertEntry(
            52L, 2L, crossDownId, "EUR Floor", "EUR/USD", -0.92, 1.09, 1.08,
            NOW.toString(), false, "cross_below", 1.08, 1.085, null
        );
        UserAlertEntry silverAlert = new UserAlertEntry(
            53L, 1L, maCrossId, "Silver Golden Cross", "SILVER/USD", 5.0, 102.0, 120.0,
            NOW.toString(), false, "ma_crossover", 110.0, 102.0, null
        );

        when(userRuleRepository.insertUserAlert(eq(1L), eq(crossUpId), eq("Gold Breakout"), eq("GOLD/USD"),
            anyDouble(), anyDouble(), anyDouble(), eq(NOW), eq("cross_above"), anyDouble(), anyDouble()))
            .thenReturn(goldAlert);
        when(userRuleRepository.insertUserAlert(eq(2L), eq(crossDownId), eq("EUR Floor"), eq("EUR/USD"),
            anyDouble(), anyDouble(), anyDouble(), eq(NOW), eq("cross_below"), anyDouble(), anyDouble()))
            .thenReturn(eurAlert);
        when(userRuleRepository.insertUserAlert(eq(1L), eq(maCrossId), eq("Silver Golden Cross"), eq("SILVER/USD"),
            anyDouble(), anyDouble(), anyDouble(), eq(NOW), eq("ma_crossover"), anyDouble(), anyDouble()))
            .thenReturn(silverAlert);

        doThrow(new AlertDeliveryException("transient error", null))
            .when(eventPublisher).publish(AlertTriggeredEvent.from(silverAlert, true));

        EvaluationSummary summary = evaluationService.evaluateTick();

        assertThat(summary.status()).isEqualTo("ok");
        assertThat(summary.evaluated()).isEqualTo(5);
        assertThat(summary.triggered()).isEqualTo(3);

        ArgumentCaptor<AlertTriggeredEvent> published = ArgumentCaptor.forClass(AlertTriggeredEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(3)).publish(published.capture());
        assertThat(published.getAllValues())
            .extracting(AlertTriggeredEvent::userId, AlertTriggeredEvent::notifyEmail)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(1L, true),
                org.assertj.core.groups.Tuple.tuple(2L, false),
                org.assertj.core.groups.Tuple.tuple(1L, true)
            );

        verify(userRuleRepository).updateLastEvaluatedRate(noBaselineId, 3690.0, NOW);
        verify(userRuleRepository).updateLastEvaluatedRate(pctRecurringId, 27000.0, NOW);
        verify(monitoringService, never()).getEffectivePreferences();
    }

    @Test
    void deliversPersonalEmailOnlyToRuleOwnerAndSkipsGlobalWebhook() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://api.resend.com/emails"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_personal"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("alice@fx.io")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Gold Breakout")))
            .andRespond(withSuccess("{\"id\":\"mail_1\"}", MediaType.APPLICATION_JSON));

        UUID ruleId = UUID.randomUUID();
        AlertEntry baseRow = new AlertEntry(70L, "GOLD/USD", 1.1, 3650.0, 3690.0, NOW.toString(), false, "cross_above", 3690.0, 3680.0);
        UserAlertEntry userRow = new UserAlertEntry(70L, 42L, ruleId, "Gold Breakout", "GOLD/USD", 1.1, 3650.0, 3690.0, NOW.toString(), false, "cross_above", 3690.0, 3680.0, null);
        when(legacyRepository.findAlertById(70L)).thenReturn(Optional.of(baseRow));
        when(userRuleRepository.findUserAlertById(70L)).thenReturn(Optional.of(userRow));
        when(userRuleRepository.findAlertEmail(42L)).thenReturn("alice@fx.io");

        AlertNotificationService notificationService = new AlertNotificationService(
            legacyRepository,
            userRuleRepository,
            monitoringService,
            builder.build(),
            "https://admin-webhook.example.com/must-not-be-called",
            "re_personal",
            "alerts@example.com"
        );

        DeliveryResult delivered = notificationService.deliver(AlertTriggeredEvent.from(userRow, true));
        assertThat(delivered).isEqualTo(new DeliveryResult("delivered", null, 70L));
        verify(legacyRepository).markAlertDelivered(70L);
        server.verify();

        // notifyEmail=false skips email completely
        AlertEntry inAppOnly = new AlertEntry(71L, "EUR/USD", -0.5, 1.09, 1.084, NOW.toString(), false, "cross_below", 1.084, 1.085);
        UserAlertEntry inAppUserRow = new UserAlertEntry(71L, 42L, ruleId, "EUR Floor", "EUR/USD", -0.5, 1.09, 1.084, NOW.toString(), false, "cross_below", 1.084, 1.085, null);
        when(legacyRepository.findAlertById(71L)).thenReturn(Optional.of(inAppOnly));
        when(userRuleRepository.findUserAlertById(71L)).thenReturn(Optional.of(inAppUserRow));
        DeliveryResult inAppResult = notificationService.deliver(AlertTriggeredEvent.from(inAppUserRow, false));
        assertThat(inAppResult.status()).isEqualTo("delivered");
        server.verify();
    }

    @Test
    void sendsPersonalTestEmailAndValidatesRuleInputs() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.resend.com/emails"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"id\":\"test_1\"}", MediaType.APPLICATION_JSON));

        when(userRuleRepository.findAlertEmail(10L)).thenReturn("");
        when(userRuleRepository.findAlertEmail(11L)).thenReturn("bob@macro.dev");

        AlertNotificationService notificationService = new AlertNotificationService(
            legacyRepository, userRuleRepository, monitoringService, builder.build(), "", "re_key", "alerts@example.com"
        );
        assertThat(notificationService.sendUserTestEmail(10L)).containsEntry("success", false);
        assertThat(notificationService.sendUserTestEmail(11L)).containsEntry("success", true);
        server.verify();

        // Validation edge cases in UserAlertRuleService
        when(userRuleRepository.findRulesByUser(anyLong())).thenReturn(List.of());
        when(marketDataService.fetchLiveRates()).thenThrow(new RuntimeException("offline"));

        AlertRuleView created = ruleService.createRule(1L, new RuleRequest(
            "gold/usd", null, "cross_above", 3700.0, null, null, null, null, null, null, null, null
        ));
        assertThat(created.pair()).isEqualTo("GOLD/USD");
        assertThat(created.frequency()).isEqualTo("ONCE");
        assertThat(created.notifyEmail()).isTrue();
        assertThat(created.spotRate()).isNull();

        assertThatThrownBy(() -> ruleService.createRule(1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("", null, "cross_above", 10.0, null, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "bad_cond", 10.0, null, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "cross_above", -1.0, null, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "pct_move", 1500.0, null, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "pct_move", 2.0, 0, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "ma_crossover", null, null, 20, 10, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "ma_crossover", null, null, 10, 20, "bad_sig", null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "cross_above", 1.2, null, null, null, null, "HOURLY", null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "cross_above", 1.2, null, null, null, null, "RECURRING", 60, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.updateProfile(1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.updateProfile(1L, new ProfileRequest("not-an-email", null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.updateProfile(1L, new ProfileRequest("a".repeat(260) + "@x.com", null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("a".repeat(70), null, "cross_above", 1.0, null, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", "n".repeat(130), "cross_above", 1.0, null, null, null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ruleService.createRule(1L, new RuleRequest("EUR/USD", null, "ma_crossover", null, null, 1, 50, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coversRuleUpdatesCooldownRemainingAndProfileEdgeCases() {
        UUID id = UUID.randomUUID();
        AlertRule recurringCooling = new AlertRule(
            id, 1L, "GOLD/USD", "Gold Watch", UserAlertRuleRepository.COND_CROSS_ABOVE,
            3700.0, 30, 20, 50, "both", UserAlertRuleRepository.FREQ_RECURRING, 3600, true,
            UserAlertRuleRepository.STATUS_ARMED, 3620.0, NOW.minusSeconds(600), NOW.minusSeconds(7200), NOW.minusSeconds(600)
        );
        AlertRule recurringExpired = new AlertRule(
            UUID.randomUUID(), 1L, "EUR/USD", "EUR Watch", UserAlertRuleRepository.COND_CROSS_BELOW,
            1.0800, 30, 20, 50, "both", UserAlertRuleRepository.FREQ_RECURRING, 3600, true,
            UserAlertRuleRepository.STATUS_ARMED, 1.0900, NOW.minusSeconds(7200), NOW.minusSeconds(9000), NOW.minusSeconds(7200)
        );
        when(userRuleRepository.findRulesByUser(1L)).thenReturn(List.of(recurringCooling, recurringExpired));
        when(marketDataService.fetchLiveRates()).thenReturn(Map.of(
            "GOLD/USD", new LiveRate(3650.0, 10.0, 0.27),
            "EUR/USD", new LiveRate(1.0950, 0.002, 0.18)
        ));

        List<AlertRuleView> views = ruleService.listRules(1L);
        assertThat(views).hasSize(2);
        assertThat(views.get(0).cooldownRemainingSeconds()).isEqualTo(3000L);
        assertThat(views.get(1).cooldownRemainingSeconds()).isZero();

        // Update all fields + invalid update cases
        assertThatThrownBy(() -> ruleService.updateRule(1L, id, null)).isInstanceOf(IllegalArgumentException.class);
        when(userRuleRepository.findRuleByIdForUser(id, 99L)).thenReturn(Optional.empty());
        assertThat(ruleService.updateRule(99L, id, new RuleRequest(null, null, null, null, null, null, null, null, null, null, null, "PAUSED")))
            .isEmpty();

        when(userRuleRepository.findRuleByIdForUser(id, 1L)).thenReturn(Optional.of(recurringCooling));
        when(userRuleRepository.updateRuleForUser(any(AlertRule.class))).thenReturn(true);
        Optional<AlertRuleView> updated = ruleService.updateRule(1L, id, new RuleRequest(
            "SILVER/USD", "Silver MA", "ma_crossover", null, 14, 10, 30, "golden_cross", "ONCE", 7200, false, "PAUSED"
        ));
        assertThat(updated).isPresent();
        assertThat(updated.get().pair()).isEqualTo("SILVER/USD");
        assertThat(updated.get().conditionType()).isEqualTo("ma_crossover");
        assertThat(updated.get().status()).isEqualTo("PAUSED");
        assertThat(updated.get().notifyEmail()).isFalse();

        assertThatThrownBy(() -> ruleService.updateRule(1L, id, new RuleRequest(
            null, null, null, null, null, null, null, null, null, null, null, "INVALID"
        ))).isInstanceOf(IllegalArgumentException.class);

        // Profile clearing email + deduplicating favorites
        when(userRuleRepository.findAlertEmail(1L)).thenReturn("");
        when(userRuleRepository.listFavoritePairs(1L)).thenReturn(List.of("GOLD/USD"));
        assertThat(ruleService.updateProfile(1L, new ProfileRequest("   ", List.of("gold/usd", " ", "GOLD/USD"))).alertEmail())
            .isEmpty();

        // Max rules per user cap
        List<AlertRule> maxRules = java.util.Collections.nCopies(100, recurringCooling);
        when(userRuleRepository.findRulesByUser(2L)).thenReturn(maxRules);
        assertThatThrownBy(() -> ruleService.createRule(2L, new RuleRequest(
            "GOLD/USD", null, "cross_above", 3700.0, null, null, null, null, null, null, null, null
        ))).isInstanceOf(IllegalArgumentException.class);
    }
}
