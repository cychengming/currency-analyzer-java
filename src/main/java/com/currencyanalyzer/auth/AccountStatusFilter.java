package com.currencyanalyzer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces live account status and role updates on active HTTP sessions so disabling a user
 * or changing their role takes effect on their very next API call.
 * Not registered as a Spring bean so Spring Boot executes it inside the SecurityFilterChain
 * after session SecurityContext hydration.
 */
public final class AccountStatusFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final KnowledgeAdminService knowledgeAdminService;

    public AccountStatusFilter(UserRepository userRepository, KnowledgeAdminService knowledgeAdminService) {
        this.userRepository = userRepository;
        this.knowledgeAdminService = knowledgeAdminService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        return "/api/auth/login".equals(path)
            || "/api/auth/register".equals(path)
            || "/api/auth/csrf".equals(path);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<UserAccount> maybeAccount = userRepository.findByUsername(authentication.getName());
        if (maybeAccount.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        UserAccount account = maybeAccount.get();
        if (!account.isActive()) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"This account has been disabled\"}");
            return;
        }

        UserRole effectiveRole = account.role().isAdmin() || knowledgeAdminService.isAdminUsername(account.username())
            ? UserRole.ADMIN
            : account.role();
        if (!authoritiesMatch(authentication, effectiveRole)) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(
                authentication.getPrincipal(),
                authentication.getCredentials(),
                effectiveRole.authorities()
            ));
            SecurityContextHolder.setContext(context);
        }

        filterChain.doFilter(request, response);
    }

    private static boolean authoritiesMatch(Authentication authentication, UserRole expectedRole) {
        Set<String> current = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        Set<String> expected = expectedRole.authorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toSet());
        return current.equals(expected);
    }
}
