package com.currencyanalyzer.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class CompatiblePbkdf2PasswordEncoder implements PasswordEncoder {

    private static final String PREFIX = "pbkdf2-sha256";
    private static final int CURRENT_ITERATIONS = 600_000;
    private static final int LEGACY_ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String encode(CharSequence rawPassword) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, CURRENT_ITERATIONS);
        return PREFIX + "$" + CURRENT_ITERATIONS + "$"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
            + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }

        if (encodedPassword.startsWith(PREFIX + "$")) {
            return matchesCurrent(rawPassword, encodedPassword);
        }
        return matchesLegacy(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null || !encodedPassword.startsWith(PREFIX + "$")) {
            return true;
        }

        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 4) {
            return true;
        }

        try {
            return Integer.parseInt(parts[1]) < CURRENT_ITERATIONS;
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    private boolean matchesCurrent(CharSequence rawPassword, String encodedPassword) {
        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean matchesLegacy(CharSequence rawPassword, String encodedPassword) {
        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 2) {
            return false;
        }

        byte[] actual = derive(
            rawPassword,
            parts[0].getBytes(StandardCharsets.UTF_8),
            LEGACY_ITERATIONS
        );
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(parts[1]);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(CharSequence rawPassword, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toString().toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("PBKDF2 is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
