package com.currencyanalyzer.pubsub;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class PubSubAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORITY_PUBSUB_PUSH = "ROLE_PUBSUB_PUSH";

    private final PubSubOidcVerifier oidcVerifier;

    public PubSubAuthenticationFilter(PubSubOidcVerifier oidcVerifier) {
        this.oidcVerifier = oidcVerifier;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        oidcVerifier.verifyAuthorizationHeader(authHeader).ifPresent(email -> {
            PreAuthenticatedAuthenticationToken authentication = new PreAuthenticatedAuthenticationToken(
                email,
                null,
                List.of(new SimpleGrantedAuthority(AUTHORITY_PUBSUB_PUSH))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        });
        filterChain.doFilter(request, response);
    }
}
