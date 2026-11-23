package com.currencyanalyzer.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final KnowledgeAdminService knowledgeAdminService;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this(userRepository, "");
    }

    public DatabaseUserDetailsService(UserRepository userRepository, String adminUsersCsv) {
        this(userRepository, new KnowledgeAdminService(userRepository, adminUsersCsv));
    }

    @Autowired
    public DatabaseUserDetailsService(UserRepository userRepository, KnowledgeAdminService knowledgeAdminService) {
        this.userRepository = userRepository;
        this.knowledgeAdminService = knowledgeAdminService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userRepository.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (knowledgeAdminService.isAdminUsername(account.username())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        return new User(
            account.username(),
            account.passwordHash(),
            true,
            true,
            true,
            true,
            List.copyOf(authorities)
        );
    }

    public static Set<String> parseAdminUsers(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Set.of(csv.split(",")).stream()
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }
}
