package com.currencyanalyzer.pubsub;

import com.currencyanalyzer.alerts.AlertDeliveryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.TokenVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PubSubOidcAndPublisherTests {

    private static final String AUDIENCE = "https://currency-analyzer-java.example.run.app";
    private static final String SERVICE_ACCOUNT = "pubsub-invoker@project-3806d7a4-0bd7-416f-8c4.iam.gserviceaccount.com";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T10:00:00Z");

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
    }

    @Test
    void verifiesValidGoogleOidcTokensAndRejectsInvalidOnes() throws Exception {
        TokenVerifier tokenVerifier = TokenVerifier.newBuilder()
            .setAudience(AUDIENCE)
            .setPublicKey(keyPair.getPublic())
            .setClock(() -> FIXED_NOW.toEpochMilli())
            .build();
        PubSubOidcVerifier verifier = new PubSubOidcVerifier(true, AUDIENCE, SERVICE_ACCOUNT, tokenVerifier);

        assertThat(verifier.isConfigured()).isTrue();

        String validJwt = signJwt(keyPair.getPrivate(), "https://accounts.google.com", AUDIENCE, SERVICE_ACCOUNT, true, 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + validJwt)).contains(SERVICE_ACCOUNT);

        String altIssuerJwt = signJwt(keyPair.getPrivate(), "accounts.google.com", AUDIENCE, SERVICE_ACCOUNT, "true", 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + altIssuerJwt)).contains(SERVICE_ACCOUNT);

        assertThat(verifier.verifyAuthorizationHeader(null)).isEmpty();
        assertThat(verifier.verifyAuthorizationHeader("Basic abc")).isEmpty();
        assertThat(verifier.verifyAuthorizationHeader("Bearer   ")).isEmpty();

        String wrongIssuer = signJwt(keyPair.getPrivate(), "https://evil.example.com", AUDIENCE, SERVICE_ACCOUNT, true, 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + wrongIssuer)).isEmpty();

        String wrongAudience = signJwt(keyPair.getPrivate(), "https://accounts.google.com", "https://other.run.app", SERVICE_ACCOUNT, true, 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + wrongAudience)).isEmpty();

        String wrongEmail = signJwt(keyPair.getPrivate(), "https://accounts.google.com", AUDIENCE, "other@example.com", true, 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + wrongEmail)).isEmpty();

        String unverifiedEmail = signJwt(keyPair.getPrivate(), "https://accounts.google.com", AUDIENCE, SERVICE_ACCOUNT, false, 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + unverifiedEmail)).isEmpty();

        String wrongSignature = signJwt(otherKeyPair.getPrivate(), "https://accounts.google.com", AUDIENCE, SERVICE_ACCOUNT, true, 300);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + wrongSignature)).isEmpty();

        String expired = signJwt(keyPair.getPrivate(), "https://accounts.google.com", AUDIENCE, SERVICE_ACCOUNT, true, -600);
        assertThat(verifier.verifyAuthorizationHeader("Bearer " + expired)).isEmpty();

        PubSubOidcVerifier disabled = new PubSubOidcVerifier(false, AUDIENCE, SERVICE_ACCOUNT, tokenVerifier);
        assertThat(disabled.isConfigured()).isFalse();
        assertThat(disabled.verifyAuthorizationHeader("Bearer " + validJwt)).isEmpty();

        PubSubOidcVerifier missingSa = new PubSubOidcVerifier(true, AUDIENCE, "", tokenVerifier);
        assertThat(missingSa.isConfigured()).isFalse();
        assertThat(missingSa.verifyAuthorizationHeader("Bearer " + validJwt)).isEmpty();
    }

    @Test
    void resolvesProxyEnvironmentVariablesSafely() {
        assertThat(GoogleHttpSupport.resolveProxyAddress(key -> null)).isNull();
        assertThat(GoogleHttpSupport.createTransportFactory(key -> null)).isNull();
        assertThat(GoogleHttpSupport.resolveProxyAddress(key -> "HTTPS_PROXY".equals(key) ? "http://127.0.0.1:7890" : null))
            .isEqualTo(new InetSocketAddress("127.0.0.1", 7890));
        assertThat(GoogleHttpSupport.resolveProxyAddress(key -> "http_proxy".equals(key) ? "127.0.0.1:8080" : null))
            .isEqualTo(new InetSocketAddress("127.0.0.1", 8080));
        assertThat(GoogleHttpSupport.createTransportFactory(key -> "HTTPS_PROXY".equals(key) ? "http://127.0.0.1:7890" : null))
            .isNotNull();
        assertThat(GoogleHttpSupport.resolveProxyAddress(key -> "HTTPS_PROXY".equals(key) ? "not a uri" : null)).isNull();
        assertThat(GoogleHttpSupport.resolveProxyAddress(key -> "HTTPS_PROXY".equals(key) ? "http://127.0.0.1" : null)).isNull();
    }

    @Test
    void publishesBase64EncodedEventToGcpPubSubRestEndpoint() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        AlertTriggeredEvent event = new AlertTriggeredEvent(
            42L,
            "NDX/USD",
            "percentage_change",
            6.86,
            25028.98,
            26746.10,
            6.86,
            2.0,
            "2026-11-24T10:00:00Z"
        );

        server.expect(requestTo("https://pubsub.googleapis.com/v1/projects/demo-project/topics/currency-alerts:publish"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer gcp-token"))
            .andExpect(request -> {
                JsonNode root = objectMapper.readTree(request.getBody().toString());
                JsonNode message = root.path("messages").get(0);
                assertThat(message.path("attributes").path("alertId").asText()).isEqualTo("42");
                assertThat(message.path("attributes").path("pair").asText()).isEqualTo("NDX/USD");
                assertThat(message.path("attributes").path("alertType").asText()).isEqualTo("percentage_change");

                byte[] decoded = Base64.getDecoder().decode(message.path("data").asText());
                AlertTriggeredEvent parsed = objectMapper.readValue(
                    new String(decoded, StandardCharsets.UTF_8),
                    AlertTriggeredEvent.class
                );
                assertThat(parsed).isEqualTo(event);
            })
            .andRespond(withSuccess("{\"messageIds\":[\"1001\"]}", MediaType.APPLICATION_JSON));

        GcpPubSubRestAlertEventPublisher publisher = new GcpPubSubRestAlertEventPublisher(
            builder.build(),
            "demo-project",
            "currency-alerts",
            () -> "gcp-token",
            objectMapper
        );
        publisher.publish(event);
        server.verify();

        assertThatThrownBy(() -> publisher.publish(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrapsPubSubRestFailuresInAlertDeliveryException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://pubsub.googleapis.com/v1/projects/demo-project/topics/currency-alerts:publish"))
            .andRespond(withServerError());

        GcpPubSubRestAlertEventPublisher publisher = new GcpPubSubRestAlertEventPublisher(
            builder.build(),
            "demo-project",
            "currency-alerts",
            () -> "gcp-token",
            new ObjectMapper()
        );
        AlertTriggeredEvent event = new AlertTriggeredEvent(
            1L, "EUR/USD", "price_threshold", 0.5, 1.10, 1.105, 1.105, 1.10, "2026-11-24T10:00:00Z"
        );

        assertThatThrownBy(() -> publisher.publish(event))
            .isInstanceOf(AlertDeliveryException.class);
        server.verify();
    }

    @Test
    void configurationSelectsPublisherBasedOnFlags() {
        PubSubConfiguration config = new PubSubConfiguration();
        AlertEventPublisher disabled = config.alertEventPublisher(RestClient.builder(), false, "demo", "currency-alerts", "");
        assertThat(disabled).isNotInstanceOf(GcpPubSubRestAlertEventPublisher.class);
        disabled.publish(new AlertTriggeredEvent(1L, "EUR/USD", "price_threshold", 0.5, 1.0, 1.05, 1.05, 1.0, "2026-11-24T10:00:00Z"));
        disabled.publish(null);

        AlertEventPublisher blankTopic = config.alertEventPublisher(RestClient.builder(), true, "demo", "", "");
        assertThat(blankTopic).isNotInstanceOf(GcpPubSubRestAlertEventPublisher.class);

        AlertEventPublisher enabled = config.alertEventPublisher(RestClient.builder(), true, "demo", "currency-alerts", "");
        assertThat(enabled).isInstanceOf(GcpPubSubRestAlertEventPublisher.class);
    }

    @Test
    void coversDefaultVerifierConstructorTokenSupplierAndRestClientBuilder() {
        PubSubOidcVerifier configuredDefault = new PubSubOidcVerifier(true, AUDIENCE, SERVICE_ACCOUNT);
        assertThat(configuredDefault.isConfigured()).isTrue();

        PubSubOidcVerifier disabledDefault = new PubSubOidcVerifier(false, "", "");
        assertThat(disabledDefault.isConfigured()).isFalse();

        assertThat(GoogleHttpSupport.buildRestClient(RestClient.builder(), key -> "HTTPS_PROXY".equals(key) ? "http://127.0.0.1:7890" : null))
            .isNotNull();
        assertThatThrownBy(() -> GoogleHttpSupport.loadPubSubCredentials("nonexistent-credentials-file-12345.json"))
            .isInstanceOf(IOException.class);

        AccessToken futureToken = new AccessToken("cached-access-token", Date.from(Instant.now().plusSeconds(3600)));
        GoogleCredentials staticCreds = GoogleCredentials.create(futureToken);
        GcpPubSubRestAlertEventPublisher.CredentialsTokenSupplier supplier =
            new GcpPubSubRestAlertEventPublisher.CredentialsTokenSupplier(() -> staticCreds);
        assertThat(supplier.get()).isEqualTo("cached-access-token");
        assertThat(supplier.get()).isEqualTo("cached-access-token");

        GcpPubSubRestAlertEventPublisher.CredentialsTokenSupplier failingSupplier =
            new GcpPubSubRestAlertEventPublisher.CredentialsTokenSupplier(() -> {
                throw new IOException("credential read failed");
            });
        assertThatThrownBy(failingSupplier::get)
            .isInstanceOf(AlertDeliveryException.class);
    }

    private static String signJwt(
        PrivateKey privateKey,
        String issuer,
        String audience,
        String email,
        Object emailVerified,
        long expiresInSeconds
    ) throws Exception {
        JsonWebSignature.Header header = new JsonWebSignature.Header();
        header.setAlgorithm("RS256");
        JsonWebToken.Payload payload = new JsonWebToken.Payload();
        payload.setIssuer(issuer);
        payload.setAudience(audience);
        payload.setIssuedAtTimeSeconds(FIXED_NOW.getEpochSecond());
        payload.setExpirationTimeSeconds(FIXED_NOW.getEpochSecond() + expiresInSeconds);
        payload.putAll(Map.of("email", email, "email_verified", emailVerified));
        return JsonWebSignature.signUsingRsaSha256(privateKey, GsonFactory.getDefaultInstance(), header, payload);
    }
}
