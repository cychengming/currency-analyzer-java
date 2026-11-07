package com.currencyanalyzer.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public UserRepository(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbcClient.sql("""
                SELECT id, username, password, created_at
                FROM users
                WHERE username = :username
                """)
            .param("username", username)
            .query((rs, rowNum) -> new UserAccount(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getObject("created_at", java.time.OffsetDateTime.class) == null
                    ? null
                    : rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
            ))
            .optional();
    }

    public boolean existsByUsername(String username) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :username)")
            .param("username", username)
            .query(Boolean.class)
            .single();
    }

    public UserAccount create(String username, String passwordHash) {
        jdbcClient.sql("""
                INSERT INTO users (username, password, created_at)
                VALUES (:username, :password, :createdAt)
                """)
            .param("username", username)
            .param("password", passwordHash)
            .param("createdAt", java.time.OffsetDateTime.now(clock))
            .update();

        return findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("Registered user could not be loaded"));
    }

    public void updatePasswordHash(long userId, String passwordHash) {
        jdbcClient.sql("""
                UPDATE users SET password = :password WHERE id = :id
                """)
            .param("password", passwordHash)
            .param("id", userId)
            .update();
    }
}
