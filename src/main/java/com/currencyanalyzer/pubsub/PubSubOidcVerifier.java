package com.currencyanalyzer.pubsub;

import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.TokenVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class PubSubOidcVerifier {

    private static final Logger log = LoggerFactory.getLogger(PubSubOidcVerifier.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> VALID_ISSUERS = Set.of(
        "https://accounts.google.com",
        "accounts.google.com"
    );

    private final boolean enabled;
    private final String expectedAudience;
    private final String expectedServiceAccount;
    private final TokenVerifier tokenVerifier;

    @Autowired
    public PubSubOidcVerifier(
        @Value("${app.pubsub.enabled:false}") boolean enabled,
        @Value("${app.pubsub.push-audience:}") String expectedAudience,
        @Value("${app.pubsub.push-service-account:}") String expectedServiceAccount
    ) {
        this(
            enabled,
            expectedAudience,
            expectedServiceAccount,
            buildDefaultVerifier(enabled, expectedAudience)
        );
    }

    PubSubOidcVerifier(
        boolean enabled,
        String expectedAudience,
        String expectedServiceAccount,
        TokenVerifier tokenVerifier
    ) {
        this.enabled = enabled;
        this.expectedAudience = trim(expectedAudience);
        this.expectedServiceAccount = trim(expectedServiceAccount).toLowerCase(Locale.ROOT);
        this.tokenVerifier = tokenVerifier;
    }

    public boolean isConfigured() {
        return enabled
            && !expectedAudience.isEmpty()
            && !expectedServiceAccount.isEmpty()
            && tokenVerifier != null;
    }

    public Optional<String> verifyAuthorizationHeader(String authorizationHeader) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String rawToken = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            return Optional.empty();
        }

        try {
            JsonWebSignature signature = tokenVerifier.verify(rawToken);
            JsonWebToken.Payload payload = signature.getPayload();
            if (payload == null) {
                return Optional.empty();
            }
            if (!VALID_ISSUERS.contains(trim(payload.getIssuer()))) {
                log.warn("Rejected Pub/Sub OIDC token with unexpected issuer");
                return Optional.empty();
            }
            if (!isEmailVerified(payload.get("email_verified"))) {
                log.warn("Rejected Pub/Sub OIDC token without verified email claim");
                return Optional.empty();
            }
            String email = trim(payload.get("email") == null ? null : payload.get("email").toString())
                .toLowerCase(Locale.ROOT);
            if (!expectedServiceAccount.equals(email)) {
                log.warn("Rejected Pub/Sub OIDC token with unexpected service account");
                return Optional.empty();
            }
            return Optional.of(email);
        } catch (TokenVerifier.VerificationException | RuntimeException exception) {
            log.warn("Rejected invalid Pub/Sub OIDC token: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private static TokenVerifier buildDefaultVerifier(boolean enabled, String expectedAudience) {
        String audience = trim(expectedAudience);
        if (!enabled || audience.isEmpty()) {
            return null;
        }
        TokenVerifier.Builder builder = TokenVerifier.newBuilder().setAudience(audience);
        HttpTransportFactory transportFactory = GoogleHttpSupport.createTransportFactory();
        if (transportFactory != null) {
            builder.setHttpTransportFactory(transportFactory);
        }
        return builder.build();
    }

    private static boolean isEmailVerified(Object claim) {
        if (claim instanceof Boolean verified) {
            return verified;
        }
        return claim != null && "true".equalsIgnoreCase(claim.toString().trim());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
