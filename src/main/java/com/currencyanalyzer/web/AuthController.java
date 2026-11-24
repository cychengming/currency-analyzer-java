package com.currencyanalyzer.web;

import com.currencyanalyzer.auth.AuthenticationService;
import com.currencyanalyzer.auth.CompatiblePbkdf2PasswordEncoder;
import com.currencyanalyzer.auth.KnowledgeAdminService;
import com.currencyanalyzer.auth.LoginAttemptLimiter;
import com.currencyanalyzer.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final KnowledgeAdminService knowledgeAdminService;
    private final AuthenticationService authenticationService;
    private final CompatiblePbkdf2PasswordEncoder passwordEncoder;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final boolean registrationEnabled;
    private final SecurityContextRepository securityContextRepository =
        new HttpSessionSecurityContextRepository();

    public AuthController(
        UserRepository userRepository,
        KnowledgeAdminService knowledgeAdminService,
        AuthenticationService authenticationService,
        CompatiblePbkdf2PasswordEncoder passwordEncoder,
        CsrfTokenRepository csrfTokenRepository,
        LoginAttemptLimiter loginAttemptLimiter,
        @Value("${app.auth.registration-enabled:true}") boolean registrationEnabled
    ) {
        this.userRepository = userRepository;
        this.knowledgeAdminService = knowledgeAdminService;
        this.authenticationService = authenticationService;
        this.passwordEncoder = passwordEncoder;
        this.csrfTokenRepository = csrfTokenRepository;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.registrationEnabled = registrationEnabled;
    }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
        return Map.of(
            "token", token.getToken(),
            "headerName", token.getHeaderName(),
            "parameterName", token.getParameterName()
        );
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) Credentials credentials) {
        if (!registrationEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(failure("Registration is disabled"));
        }

        String username = trim(credentials == null ? null : credentials.username());
        String password = trim(credentials == null ? null : credentials.password());

        if (username.isEmpty() || password.isEmpty()) {
            return ResponseEntity.ok(failure("Username and password required"));
        }
        if (username.length() < 3) {
            return ResponseEntity.ok(failure("Username must be at least 3 characters"));
        }
        if (password.length() < 6) {
            return ResponseEntity.ok(failure("Password must be at least 6 characters"));
        }
        if (knowledgeAdminService.isReservedAdminName(username)) {
            return ResponseEntity.ok(failure("This username is reserved and cannot be registered"));
        }
        if (userRepository.existsByUsername(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(failure("Username already exists"));
        }

        try {
            userRepository.create(username, passwordEncoder.encode(password));
            return ResponseEntity.ok(Map.of("success", true, "message", "Registration successful"));
        } catch (DuplicateKeyException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(failure("Username already exists"));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
        @RequestBody(required = false) Credentials credentials,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String clientKey = clientKey(request);
        if (loginAttemptLimiter.isBlocked(clientKey)) {
            log.warn("Rate-limited repeated failed login attempts");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(failure("Too many failed login attempts. Please try again later."));
        }

        String username = trim(credentials == null ? null : credentials.username());
        String password = trim(credentials == null ? null : credentials.password());

        if (username.isEmpty() || password.isEmpty()) {
            return ResponseEntity.ok(failure("Username and password required"));
        }

        try {
            Authentication authentication = authenticationService.authenticate(username, password);
            loginAttemptLimiter.recordSuccess(clientKey);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            request.getSession(true).setAttribute("username", authentication.getName());
            return ResponseEntity.ok(Map.of("success", true, "message", "Login successful"));
        } catch (BadCredentialsException exception) {
            loginAttemptLimiter.recordFailure(clientKey);
            log.info("Login failed for username={}", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(failure("Invalid username or password"));
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("success", true, "message", "Logged out");
    }

    private static String clientKey(HttpServletRequest request) {
        if (request == null || request.getRemoteAddr() == null) {
            return "";
        }
        return request.getRemoteAddr().trim();
    }

    private static Map<String, Object> failure(String message) {
        return Map.of("success", false, "error", message);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Credentials(String username, String password) {
    }
}
