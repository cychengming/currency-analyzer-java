package com.currencyanalyzer.web;

import com.currencyanalyzer.auth.AuthenticationService;
import com.currencyanalyzer.auth.CompatiblePbkdf2PasswordEncoder;
import com.currencyanalyzer.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
    private final AuthenticationService authenticationService;
    private final CompatiblePbkdf2PasswordEncoder passwordEncoder;
    private final CsrfTokenRepository csrfTokenRepository;
    private final SecurityContextRepository securityContextRepository =
        new HttpSessionSecurityContextRepository();

    public AuthController(
        UserRepository userRepository,
        AuthenticationService authenticationService,
        CompatiblePbkdf2PasswordEncoder passwordEncoder,
        CsrfTokenRepository csrfTokenRepository
    ) {
        this.userRepository = userRepository;
        this.authenticationService = authenticationService;
        this.passwordEncoder = passwordEncoder;
        this.csrfTokenRepository = csrfTokenRepository;
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
    public Map<String, Object> register(@RequestBody(required = false) Credentials credentials) {
        String username = trim(credentials == null ? null : credentials.username());
        String password = trim(credentials == null ? null : credentials.password());

        if (username.isEmpty() || password.isEmpty()) {
            return failure("Username and password required");
        }
        if (username.length() < 3) {
            return failure("Username must be at least 3 characters");
        }
        if (password.length() < 6) {
            return failure("Password must be at least 6 characters");
        }
        if (userRepository.existsByUsername(username)) {
            return failure("Username already exists");
        }

        try {
            userRepository.create(username, passwordEncoder.encode(password));
            return Map.of("success", true, "message", "Registration successful");
        } catch (DuplicateKeyException exception) {
            return failure("Username already exists");
        }
    }

    @PostMapping("/login")
    public Map<String, Object> login(
        @RequestBody(required = false) Credentials credentials,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        String username = trim(credentials == null ? null : credentials.username());
        String password = trim(credentials == null ? null : credentials.password());

        if (username.isEmpty() || password.isEmpty()) {
            return failure("Username and password required");
        }

        try {
            Authentication authentication = authenticationService.authenticate(username, password);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            request.getSession(true).setAttribute("username", authentication.getName());
            return Map.of("success", true, "message", "Login successful");
        } catch (BadCredentialsException exception) {
            log.info("Login failed for username={}", username);
            return failure("Invalid username or password");
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

    private static Map<String, Object> failure(String message) {
        return Map.of("success", false, "error", message);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record Credentials(String username, String password) {
    }
}
