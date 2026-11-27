package com.currencyanalyzer.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and validates DB-anchored HMAC-SHA256 persistent authentication cookies
 * so authenticated user sessions survive Cloud Run instance restarts and scale-to-zero
 * without relying solely on volatile in-memory Tomcat {@code HttpSession} state.
 */
@Service
public class PersistentSessionService {

    public static final String COOKIE_NAME = "CA_SESSION";

    private static final Logger log = LoggerFactory.getLogger(PersistentSessionService.class);
    private static final String TOKEN_VERSION = "v1";
    private static final String SECRET_SETTING_KEY = "internal_auth_session_secret";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final JdbcClient jdbcClient;
    private final UserRepository userRepository;
    private final KnowledgeAdminService knowledgeAdminService;
    private final Clock clock;
    private final String configuredSecret;
    private final boolean secureCookie;
    private final Duration sessionTimeout;

    private volatile byte[] cachedSecret;

    public PersistentSessionService(
        JdbcClient jdbcClient,
        UserRepository userRepository,
        KnowledgeAdminService knowledgeAdminService,
        Clock clock,
        @Value("${app.auth.session-secret:}") String configuredSecret,
        @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
        @Value("${server.servlet.session.timeout:7d}") Duration sessionTimeout
    ) {
        this.jdbcClient = jdbcClient;
        this.userRepository = userRepository;
        this.knowledgeAdminService = knowledgeAdminService;
        this.clock = clock;
        this.configuredSecret = configuredSecret == null ? "" : configuredSecret.trim();
        this.secureCookie = secureCookie;
        this.sessionTimeout = sessionTimeout != null && !sessionTimeout.isNegative() && !sessionTimeout.isZero()
            ? sessionTimeout
            : Duration.ofDays(7);
    }

    public void issueSessionCookie(HttpServletResponse response, String username) {
        if (response == null || username == null || username.isBlank()) {
            return;
        }
        Optional<UserAccount> maybeAccount = userRepository.findByUsername(username);
        if (maybeAccount.isEmpty() || !maybeAccount.get().isActive()) {
            return;
        }
        UserAccount account = maybeAccount.get();
        long expiresEpochSeconds = clock.instant().plus(sessionTimeout).getEpochSecond();
        String encodedUser = B64_ENCODER.encodeToString(account.username().getBytes(StandardCharsets.UTF_8));
        String macHex = HEX.formatHex(computeHmac(account, expiresEpochSeconds));
        String token = TOKEN_VERSION + "." + encodedUser + "." + expiresEpochSeconds + "." + macHex;

        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(sessionTimeout)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public void clearSessionCookie(HttpServletResponse response) {
        if (response == null) {
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public Optional<UserAccount> validateRequestCookie(HttpServletRequest request, HttpServletResponse response) {
        String token = extractCookieToken(request);
        if (token == null) {
            return Optional.empty();
        }

        ParsedToken parsed = parseToken(token);
        if (parsed == null || parsed.expiresEpochSeconds() <= clock.instant().getEpochSecond()) {
            clearSessionCookie(response);
            return Optional.empty();
        }

        byte[] providedMac;
        try {
            providedMac = HEX.parseHex(parsed.macHex());
        } catch (IllegalArgumentException exception) {
            clearSessionCookie(response);
            return Optional.empty();
        }

        Optional<UserAccount> maybeAccount = userRepository.findByUsername(parsed.username());
        if (maybeAccount.isEmpty()) {
            clearSessionCookie(response);
            return Optional.empty();
        }

        UserAccount account = maybeAccount.get();
        byte[] expectedMac = computeHmac(account, parsed.expiresEpochSeconds());
        if (!MessageDigest.isEqual(expectedMac, providedMac)) {
            clearSessionCookie(response);
            return Optional.empty();
        }

        if (!account.isActive()) {
            clearSessionCookie(response);
        }
        return Optional.of(account);
    }

    public UserRole resolveEffectiveRole(UserAccount account) {
        if (account.role().isAdmin() || knowledgeAdminService.isAdminUsername(account.username())) {
            return UserRole.ADMIN;
        }
        return account.role();
    }

    private static String extractCookieToken(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private static ParsedToken parseToken(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !TOKEN_VERSION.equals(parts[0])) {
            return null;
        }
        try {
            byte[] decodedUser = B64_DECODER.decode(parts[1]);
            String username = new String(decodedUser, StandardCharsets.UTF_8);
            if (username.isBlank() || parts[3].isBlank()) {
                return null;
            }
            long expiresEpochSeconds = Long.parseLong(parts[2]);
            return new ParsedToken(username, expiresEpochSeconds, parts[3]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private byte[] computeHmac(UserAccount account, long expiresEpochSeconds) {
        String payload = TOKEN_VERSION
            + "|" + account.id()
            + "|" + account.username()
            + "|" + expiresEpochSeconds
            + "|" + sha256Hex(account.passwordHash());
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(resolveSecretBytes(), HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to sign persistent session token", exception);
        }
    }

    private byte[] resolveSecretBytes() {
        byte[] existing = cachedSecret;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (cachedSecret != null) {
                return cachedSecret;
            }
            if (!configuredSecret.isEmpty()) {
                cachedSecret = sha256Bytes(configuredSecret);
                return cachedSecret;
            }
            String storedHex = findStoredSecretHex().orElseGet(this::generateAndPersistSecretHex);
            cachedSecret = HEX.parseHex(storedHex);
            return cachedSecret;
        }
    }

    private Optional<String> findStoredSecretHex() {
        return jdbcClient.sql("SELECT value FROM settings WHERE key = :key")
            .param("key", SECRET_SETTING_KEY)
            .query(String.class)
            .optional()
            .map(String::trim)
            .filter(value -> value.length() == 64);
    }

    private String generateAndPersistSecretHex() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String generatedHex = HEX.formatHex(randomBytes);
        try {
            jdbcClient.sql("INSERT INTO settings (key, value) VALUES (:key, :value)")
                .param("key", SECRET_SETTING_KEY)
                .param("value", generatedHex)
                .update();
            return generatedHex;
        } catch (DataAccessException exception) {
            log.debug("Concurrent insert of persistent session secret, reading existing row");
            return findStoredSecretHex().orElse(generatedHex);
        }
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha256Hex(String value) {
        return HEX.formatHex(sha256Bytes(value));
    }

    private record ParsedToken(String username, long expiresEpochSeconds, String macHex) {
    }
}
