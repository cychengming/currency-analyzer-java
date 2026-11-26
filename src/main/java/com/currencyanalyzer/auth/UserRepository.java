package com.currencyanalyzer.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public UserRepository(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public Optional<UserAccount> findOldest() {
        return jdbcClient.sql("""
                SELECT id, username, password, role, status, display_name, created_at, last_login_at
                FROM users
                ORDER BY created_at ASC, id ASC
                LIMIT 1
                """)
            .query((rs, rowNum) -> mapAccount(rs))
            .optional();
    }

    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                SELECT id, username, password, role, status, display_name, created_at, last_login_at
                FROM users
                WHERE LOWER(username) = LOWER(:username)
                ORDER BY id ASC
                LIMIT 1
                """)
            .param("username", username.trim())
            .query((rs, rowNum) -> mapAccount(rs))
            .optional();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbcClient.sql("""
                SELECT id, username, password, role, status, display_name, created_at, last_login_at
                FROM users
                WHERE id = :id
                """)
            .param("id", id)
            .query((rs, rowNum) -> mapAccount(rs))
            .optional();
    }

    public List<UserAccount> findAll() {
        return jdbcClient.sql("""
                SELECT id, username, password, role, status, display_name, created_at, last_login_at
                FROM users
                ORDER BY created_at ASC, id ASC
                """)
            .query((rs, rowNum) -> mapAccount(rs))
            .list();
    }

    public long countAll() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM users")
            .query(Long.class)
            .single();
        return count == null ? 0L : count;
    }

    public long countActiveAdmins() {
        Long count = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM users
                WHERE role = 'ADMIN' AND status = 'ACTIVE'
                """)
            .query(Long.class)
            .single();
        return count == null ? 0L : count;
    }

    public boolean existsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM users WHERE LOWER(username) = LOWER(:username))")
            .param("username", username.trim())
            .query(Boolean.class)
            .single();
    }

    public UserAccount create(String username, String passwordHash) {
        return create(username, passwordHash, UserRole.MEMBER, null);
    }

    public UserAccount create(String username, String passwordHash, UserRole role, String displayName) {
        UserRole effectiveRole = role == null ? UserRole.MEMBER : role;
        String cleanDisplay = displayName == null || displayName.isBlank() ? null : displayName.trim();
        jdbcClient.sql("""
                INSERT INTO users (username, password, role, status, display_name, created_at)
                VALUES (:username, :password, :role, 'ACTIVE', :displayName, :createdAt)
                """)
            .param("username", username)
            .param("password", passwordHash)
            .param("role", effectiveRole.name())
            .param("displayName", cleanDisplay)
            .param("createdAt", OffsetDateTime.now(clock))
            .update();

        return findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("Registered user could not be loaded"));
    }

    public void updateRoleStatusAndDisplayName(long userId, UserRole role, String status, String displayName) {
        jdbcClient.sql("""
                UPDATE users
                SET role = :role, status = :status, display_name = :displayName
                WHERE id = :id
                """)
            .param("role", role.name())
            .param("status", status)
            .param("displayName", displayName == null || displayName.isBlank() ? null : displayName.trim())
            .param("id", userId)
            .update();
    }

    public void recordLastLogin(long userId) {
        jdbcClient.sql("""
                UPDATE users SET last_login_at = :lastLoginAt WHERE id = :id
                """)
            .param("lastLoginAt", OffsetDateTime.now(clock))
            .param("id", userId)
            .update();
    }

    public void updatePasswordHash(long userId, String passwordHash) {
        jdbcClient.sql("""
                UPDATE users SET password = :password WHERE id = :id
                """)
            .param("password", passwordHash)
            .param("id", userId)
            .update();
    }

    private static UserAccount mapAccount(ResultSet rs) throws SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        OffsetDateTime lastLoginAt = rs.getObject("last_login_at", OffsetDateTime.class);
        return new UserAccount(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password"),
            UserRole.fromDatabase(rs.getString("role")),
            rs.getString("status"),
            rs.getString("display_name"),
            createdAt == null ? null : createdAt.toInstant(),
            lastLoginAt == null ? null : lastLoginAt.toInstant()
        );
    }
}
