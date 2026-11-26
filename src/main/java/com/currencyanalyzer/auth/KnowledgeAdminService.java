package com.currencyanalyzer.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Admin resolution for shared workspace, RAG, and user management operations.
 * Combines database {@link UserRole#ADMIN} assignments with {@code app.rag.admin-users}
 * allowlist overrides; when the allowlist is empty and no DB ADMIN exists yet, the oldest
 * active account acts as owner so the deployment cannot lock itself out.
 */
@Service
public class KnowledgeAdminService {

    private final UserRepository userRepository;
    private final Set<String> reservedAdminNames;

    public KnowledgeAdminService(
        UserRepository userRepository,
        @Value("${app.rag.admin-users:}") String adminUsersCsv
    ) {
        this.userRepository = userRepository;
        this.reservedAdminNames = DatabaseUserDetailsService.parseAdminUsers(adminUsersCsv);
    }

    public boolean hasConfiguredAdmins() {
        return !reservedAdminNames.isEmpty();
    }

    public boolean isReservedAdminName(String username) {
        String normalized = normalize(username);
        return !normalized.isEmpty() && reservedAdminNames.contains(normalized);
    }

    public boolean isAdminUsername(String username) {
        String normalized = normalize(username);
        if (normalized.isEmpty()) {
            return false;
        }
        Optional<UserAccount> account = userRepository.findByUsername(normalized);
        if (account.isPresent()) {
            UserAccount found = account.get();
            if (!found.isActive()) {
                return false;
            }
            if (found.role().isAdmin()) {
                return true;
            }
        }
        if (!reservedAdminNames.isEmpty()) {
            return reservedAdminNames.contains(normalized);
        }
        Optional<UserAccount> oldest = userRepository.findOldest();
        return oldest.isPresent()
            && oldest.get().isActive()
            && normalized.equals(normalize(oldest.get().username()));
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
