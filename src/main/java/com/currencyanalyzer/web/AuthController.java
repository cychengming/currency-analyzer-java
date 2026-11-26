package com.currencyanalyzer.web;

import com.currencyanalyzer.auth.AuthenticationService;
import com.currencyanalyzer.auth.KnowledgeAdminService;
import com.currencyanalyzer.auth.LoginAttemptLimiter;
import com.currencyanalyzer.auth.UserManagementService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
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

    private final UserManagementService userManagementService;
    private final KnowledgeAdminService knowledgeAdminService;
    private final AuthenticationService authenticationService;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final boolean registrationEnabled;
    private final SecurityContextRepository securityContextRepository =
        new HttpSessionSecurityContextRepository();

    public AuthController(
        UserManagementService userManagementService,
        KnowledgeAdminService knowledgeAdminService,
        AuthenticationService authenticationService,
        CsrfTokenRepository csrfTokenRepository,
        LoginAttemptLimiter loginAttemptLimiter,
        @Value("${app.auth.registration-enabled:true}") boolean registrationEnabled
    ) {
        this.userManagementService = userManagementService;
        this.knowledgeAdminService = knowledgeAdminService;
        this.authenticationService = authenticationService;
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
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) RegisterRequest request) {
        String username = trim(request == null ? null : request.username());
        String password = trim(request == null ? null : request.password());
        String inviteCode = trim(request == null ? null : request.inviteCode());
        String displayName = trim(request == null ? null : request.displayName());
        boolean hasInvite = !inviteCode.isEmpty();

        if (!registrationEnabled && !hasInvite) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(failure("Registration is disabled"));
        }
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

        try {
            userManagementService.registerUser(username, password, displayName, inviteCode, registrationEnabled);
            return ResponseEntity.ok(Map.of("success", true, "message", "Registration successful"));
        } catch (UserManagementService.DuplicateUsernameException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(failure(exception.getMessage()));
        } catch (UserManagementService.RegistrationDisabledException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(failure(exception.getMessage()));
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
        } catch (DisabledException exception) {
            log.info("Blocked login for disabled username={}", username);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(failure("This account has been disabled"));
        } catch (BadCredentialsException exception) {
            loginAttemptLimiter.recordFailure(clientKey);
            log.info("Login failed for username={}", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(failure("Invalid username or password"));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
        Authentication authentication,
        @RequestBody(required = false) ChangePasswordRequest request
    ) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(failure("Not authenticated"));
        }
        userManagementService.changeOwnPassword(
            authentication.getName(),
            request == null ? null : request.currentPassword(),
            request == null ? null : request.newPassword()
        );
        return ResponseEntity.ok(Map.of("success", true, "message", "Password updated"));
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

    public record RegisterRequest(
        String username,
        String password,
        @JsonProperty("invite_code") String inviteCode,
        @JsonProperty("display_name") String displayName
    ) {
    }

    public record ChangePasswordRequest(
        @JsonProperty("current_password") String currentPassword,
        @JsonProperty("new_password") String newPassword
    ) {
    }
}
