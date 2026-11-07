package com.currencyanalyzer.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final CompatiblePbkdf2PasswordEncoder passwordEncoder;

    public AuthenticationService(
        AuthenticationManager authenticationManager,
        UserRepository userRepository,
        CompatiblePbkdf2PasswordEncoder passwordEncoder
    ) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Authentication authenticate(String username, String password) {
        Authentication authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(username, password)
        );

        userRepository.findByUsername(username).ifPresent(account -> {
            if (passwordEncoder.upgradeEncoding(account.passwordHash())) {
                userRepository.updatePasswordHash(account.id(), passwordEncoder.encode(password));
            }
        });

        return authentication;
    }
}
