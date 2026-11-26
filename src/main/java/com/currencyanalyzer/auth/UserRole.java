package com.currencyanalyzer.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Locale;

/**
 * Three-tier application role hierarchy:
 * ADMIN (full workspace, RAG, user & invite administration),
 * MEMBER (markets, personal Alerts 2.0, AI assistant within quota),
 * VIEWER (strictly read-only access; no AI spend or mutations).
 */
public enum UserRole {
    ADMIN(List.of(
        new SimpleGrantedAuthority("ROLE_ADMIN"),
        new SimpleGrantedAuthority("ROLE_MEMBER"),
        new SimpleGrantedAuthority("ROLE_USER")
    )),
    MEMBER(List.of(
        new SimpleGrantedAuthority("ROLE_MEMBER"),
        new SimpleGrantedAuthority("ROLE_USER")
    )),
    VIEWER(List.of(
        new SimpleGrantedAuthority("ROLE_VIEWER")
    ));

    private final List<GrantedAuthority> grantedAuthorities;

    UserRole(List<GrantedAuthority> grantedAuthorities) {
        this.grantedAuthorities = grantedAuthorities;
    }

    public List<GrantedAuthority> authorities() {
        return grantedAuthorities;
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    public boolean canWriteWorkspace() {
        return this == ADMIN || this == MEMBER;
    }

    public boolean canUseAi() {
        return this == ADMIN || this == MEMBER;
    }

    public static UserRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("role is required (ADMIN, MEMBER, or VIEWER)");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> ADMIN;
            case "MEMBER" -> MEMBER;
            case "VIEWER" -> VIEWER;
            default -> throw new IllegalArgumentException("role must be ADMIN, MEMBER, or VIEWER");
        };
    }

    public static UserRole fromDatabase(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEMBER;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> ADMIN;
            case "VIEWER" -> VIEWER;
            default -> MEMBER;
        };
    }
}
