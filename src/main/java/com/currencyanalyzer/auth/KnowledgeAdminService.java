package com.currencyanalyzer.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Admin for shared RAG mutations.
 * A configured name list reserves those usernames: public registration cannot create them.
 * When the list is empty, the oldest account is the owner so the deployment is not locked out.
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

    public boolean isReservedAdminName(String username) {
        String normalized = normalize(username);
        return !normalized.isEmpty() && reservedAdminNames.contains(normalized);
    }

    public boolean isAdminUsername(String username) {
        String normalized = normalize(username);
        if (normalized.isEmpty()) {
            return false;
        }
        if (!reservedAdminNames.isEmpty()) {
            return reservedAdminNames.contains(normalized);
        }
        Optional<UserAccount> oldest = userRepository.findOldest();
        return oldest.isPresent() && normalized.equals(normalize(oldest.get().username()));
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
