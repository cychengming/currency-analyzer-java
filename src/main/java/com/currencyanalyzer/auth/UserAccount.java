package com.currencyanalyzer.auth;

import java.time.Instant;

public record UserAccount(
    long id,
    String username,
    String passwordHash,
    UserRole role,
    String status,
    String displayName,
    Instant createdAt,
    Instant lastLoginAt
) {
    public UserAccount {
        role = role == null ? UserRole.MEMBER : role;
        status = status == null || status.isBlank() ? "ACTIVE" : status;
    }

    public UserAccount(long id, String username, String passwordHash, Instant createdAt) {
        this(id, username, passwordHash, UserRole.MEMBER, "ACTIVE", null, createdAt, null);
    }

    public boolean isActive() {
        return !"DISABLED".equalsIgnoreCase(status);
    }
}
