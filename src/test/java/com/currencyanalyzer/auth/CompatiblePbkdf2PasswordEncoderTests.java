package com.currencyanalyzer.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompatiblePbkdf2PasswordEncoderTests {

    private static final String LEGACY_HASH =
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
            + "$0b4e90e41be47c73a32e193955c8d1955f31bfb499db4bf8aa29828d502a5931";

    private final CompatiblePbkdf2PasswordEncoder encoder = new CompatiblePbkdf2PasswordEncoder();

    @Test
    void verifiesLegacyFlaskPasswordHash() {
        assertThat(encoder.matches("secret123", LEGACY_HASH)).isTrue();
        assertThat(encoder.matches("incorrect", LEGACY_HASH)).isFalse();
        assertThat(encoder.upgradeEncoding(LEGACY_HASH)).isTrue();
    }

    @Test
    void createsAndVerifiesUpgradedHash() {
        String hash = encoder.encode("secret123");

        assertThat(hash).startsWith("pbkdf2-sha256$600000$");
        assertThat(encoder.matches("secret123", hash)).isTrue();
        assertThat(encoder.matches("incorrect", hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
    }
}
