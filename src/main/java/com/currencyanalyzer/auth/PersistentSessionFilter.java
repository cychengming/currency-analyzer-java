package com.currencyanalyzer.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Re-hydrates an authenticated {@link SecurityContext} and HTTP session from a valid
 * {@code CA_SESSION} cookie when the in-memory container session is absent (e.g. after a
 * Cloud Run scale-to-zero or container recycle).
 * Not registered as a Spring bean so it executes only inside the Servlet {@code SecurityFilterChain}.
 */
public final class PersistentSessionFilter extends OncePerRequestFilter {

    private final PersistentSessionService persistentSessionService;
    private final SecurityContextRepository securityContextRepository =
        new HttpSessionSecurityContextRepository();

    public PersistentSessionFilter(PersistentSessionService persistentSessionService) {
        this.persistentSessionService = persistentSessionService;
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
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated() && !(existing instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<UserAccount> maybeAccount = persistentSessionService.validateRequestCookie(request, response);
        if (maybeAccount.isPresent()) {
            UserAccount account = maybeAccount.get();
            UserRole effectiveRole = persistentSessionService.resolveEffectiveRole(account);
            User principal = new User(
                account.username(),
                "",
                account.isActive(),
                true,
                true,
                true,
                effectiveRole.authorities()
            );
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                effectiveRole.authorities()
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);

            if (account.isActive()) {
                securityContextRepository.saveContext(context, request, response);
                request.getSession(true).setAttribute("username", account.username());
            }
        }

        filterChain.doFilter(request, response);
    }
}
