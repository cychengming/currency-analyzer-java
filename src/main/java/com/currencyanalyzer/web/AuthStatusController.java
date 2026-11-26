package com.currencyanalyzer.web;

import com.currencyanalyzer.auth.KnowledgeAdminService;
import com.currencyanalyzer.auth.UserAccount;
import com.currencyanalyzer.auth.UserRepository;
import com.currencyanalyzer.auth.UserRole;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthStatusController {

    private final UserRepository userRepository;
    private final KnowledgeAdminService knowledgeAdminService;
    private final boolean registrationEnabled;

    public AuthStatusController(
        UserRepository userRepository,
        KnowledgeAdminService knowledgeAdminService,
        @Value("${app.auth.registration-enabled:true}") boolean registrationEnabled
    ) {
        this.userRepository = userRepository;
        this.knowledgeAdminService = knowledgeAdminService;
        this.registrationEnabled = registrationEnabled;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session, Authentication authentication) {
        Object sessionUsername = session.getAttribute("username");
        String resolvedUsername = sessionUsername != null
            ? String.valueOf(sessionUsername)
            : (authentication != null && authentication.isAuthenticated() ? authentication.getName() : null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("logged_in", resolvedUsername != null);
        response.put("username", resolvedUsername);
        response.put("registration_enabled", registrationEnabled);
        response.put("invite_registration_enabled", true);

        if (resolvedUsername != null) {
            UserAccount account = userRepository.findByUsername(resolvedUsername).orElse(null);
            UserRole effectiveRole = resolveRole(resolvedUsername, account, authentication);
            response.put("display_name", account == null ? null : account.displayName());
            response.put("role", effectiveRole.name());
            response.put("is_admin", effectiveRole.isAdmin());
            response.put("can_write", effectiveRole.canWriteWorkspace());
            response.put("can_use_ai", effectiveRole.canUseAi());
        }
        return response;
    }

    private UserRole resolveRole(String username, UserAccount account, Authentication authentication) {
        if ((account != null && account.role().isAdmin()) || knowledgeAdminService.isAdminUsername(username)) {
            return UserRole.ADMIN;
        }
        if (account != null) {
            return account.role();
        }
        if (authentication != null && authentication.getAuthorities() != null) {
            boolean isViewer = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_VIEWER".equals(authority.getAuthority()));
            if (isViewer) {
                return UserRole.VIEWER;
            }
        }
        return UserRole.MEMBER;
    }
}
