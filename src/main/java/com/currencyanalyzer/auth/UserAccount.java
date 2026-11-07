package com.currencyanalyzer.auth;

import java.time.Instant;

public record UserAccount(
    long id,
    String username,
    String passwordHash,
    Instant createdAt
) {
}
