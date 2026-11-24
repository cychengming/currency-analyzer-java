package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.alerts.AlertNotificationService.DeliveryResult;
import com.currencyanalyzer.pubsub.AlertTriggeredEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class AlertNotificationServiceTests {

    @Mock
    private AlertMonitoringRepository repository;
    @Mock
    private AlertMonitoringService monitoringService;

    private static AlertTriggeredEvent sampleEvent(long id) {
        return new AlertTriggeredEvent(
            id,
            "NDX/USD",
            "percentage_change",
            6.86,
            25028.98,
            26746.10,
            6.86,
            2.0,
            "2026-11-24T10:00:00Z"
        );
    }

    @Test
    void validatesEventAndSkipsMissingOrAlreadyDeliveredAlerts() {
        AlertNotificationService service = new AlertNotificationService(
            repository, monitoringService, RestClient.builder().build(), "", "", ""
        );

        assertThatThrownBy(() -> service.deliver(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deliver(sampleEvent(0))).isInstanceOf(IllegalArgumentException.class);

        when(repository.findAlertById(99L)).thenReturn(Optional.empty());
        assertThat(service.deliver(sampleEvent(99L)))
            .isEqualTo(new DeliveryResult("skipped", "alert_not_found", 99L));

        AlertEntry delivered = new AlertEntry(10L, "NDX/USD", 6.86, 25028.98, 26746.10, "2026-11-24T10:00:00Z", true, "percentage_change", 6.86, 2.0);
        when(repository.findAlertById(10L)).thenReturn(Optional.of(delivered));
        assertThat(service.deliver(sampleEvent(10L)))
            .isEqualTo(new DeliveryResult("skipped", "already_delivered", 10L));
        verify(repository, never()).markAlertDelivered(10L);
    }

    @Test
    void dispatchesWebhookAndResendEmailThenMarksDelivered() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(requestTo("https://hooks.example.com/alerts"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.resend.com/emails"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_key"))
            .andRespond(withSuccess("{\"id\":\"email_1\"}", MediaType.APPLICATION_JSON));

        AlertEntry pending = new AlertEntry(12L, "NDX/USD", 6.86, 25028.98, 26746.10, "2026-11-24T10:00:00Z", false, "percentage_change", 6.86, 2.0);
        when(repository.findAlertById(12L)).thenReturn(Optional.of(pending));
        when(monitoringService.getSettings()).thenReturn(Map.of("alert_email", "trader@example.com"));

        AlertNotificationService service = new AlertNotificationService(
            repository,
            monitoringService,
            builder.build(),
            "https://hooks.example.com/alerts",
            "re_test_key",
            "alerts@example.com"
        );

        DeliveryResult result = service.deliver(sampleEvent(12L));
        assertThat(result).isEqualTo(new DeliveryResult("delivered", null, 12L));
        verify(repository).markAlertDelivered(12L);
        server.verify();
    }

    @Test
    void throwsAlertDeliveryExceptionOnDownstreamFailureWithoutMarkingDelivered() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example.com/alerts"))
            .andRespond(withServerError());

        AlertEntry pending = new AlertEntry(15L, "NDX/USD", -2.5, 27000.0, 26325.0, "2026-11-24T10:00:00Z", false, "percentage_change", 2.5, 2.0);
        when(repository.findAlertById(15L)).thenReturn(Optional.of(pending));

        AlertNotificationService service = new AlertNotificationService(
            repository,
            monitoringService,
            builder.build(),
            "https://hooks.example.com/alerts",
            "",
            ""
        );

        assertThatThrownBy(() -> service.deliver(sampleEvent(15L)))
            .isInstanceOf(AlertDeliveryException.class);
        verify(repository, never()).markAlertDelivered(15L);
        server.verify();
    }

    @Test
    void testNotificationHandlesUnconfiguredAndConfiguredChannels() {
        when(monitoringService.getSettings()).thenReturn(Map.of("alert_email", ""));
        AlertNotificationService unconfigured = new AlertNotificationService(
            repository, monitoringService, RestClient.builder().build(), "", "", ""
        );
        Map<String, Object> notReady = unconfigured.sendTestNotification();
        assertThat(notReady).containsEntry("success", false);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://hooks.example.com/alerts"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AlertNotificationService configured = new AlertNotificationService(
            repository, monitoringService, builder.build(), "https://hooks.example.com/alerts", "", ""
        );
        Map<String, Object> ok = configured.sendTestNotification();
        assertThat(ok).containsEntry("success", true);
        server.verify();
    }
}
