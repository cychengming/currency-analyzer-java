package com.currencyanalyzer.auth;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Shared RBAC enforcement across REST controllers.
 */
@Component
public class RoleGuard {

    private final UserRepository userRepository;
    private final KnowledgeAdminService knowledgeAdminService;

    public RoleGuard(UserRepository userRepository, KnowledgeAdminService knowledgeAdminService) {
        this.userRepository = userRepository;
        this.knowledgeAdminService = knowledgeAdminService;
    }

    public void requireAdmin(Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Admin privileges are required");
        }
    }

    public void requireMemberOrAdmin(Authentication authentication, String actionDescription) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        if (isViewer(authentication)) {
            throw new AccessDeniedException(actionDescription);
        }
    }

    public boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return false;
        }
        if (hasAuthority(authentication, "ROLE_ADMIN")) {
            return true;
        }
        return knowledgeAdminService.isAdminUsername(authentication.getName());
    }

    public boolean isViewer(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return false;
        }
        if (hasAuthority(authentication, "ROLE_ADMIN")) {
            return false;
        }
        if (hasAuthority(authentication, "ROLE_VIEWER")) {
            return true;
        }
        return userRepository.findByUsername(authentication.getName())
            .map(account -> account.role() == UserRole.VIEWER && !knowledgeAdminService.isAdminUsername(account.username()))
            .orElse(false);
    }

    private static boolean hasAuthority(Authentication authentication, String expected) {
        return authentication.getAuthorities() != null
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }
}
