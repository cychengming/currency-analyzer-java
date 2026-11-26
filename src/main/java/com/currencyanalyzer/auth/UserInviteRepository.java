package com.currencyanalyzer.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserInviteRepository {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcClient jdbcClient;

    public UserInviteRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public static String generateInviteCode() {
        StringBuilder raw = new StringBuilder(12);
        for (int index = 0; index < 12; index++) {
            raw.append(CODE_ALPHABET.charAt(SECURE_RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return "CA-" + raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
    }

    public static String normalizeCode(String rawCode) {
        if (rawCode == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(rawCode.length());
        for (int index = 0; index < rawCode.length(); index++) {
            char ch = Character.toUpperCase(rawCode.charAt(index));
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                clean.append(ch);
            }
        }
        return clean.toString();
    }

    public static String sha256Hex(String normalizedCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalizedCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    public InviteRecord insert(
        String codePrefix,
        String tokenHash,
        UserRole role,
        String note,
        String createdBy,
        Instant expiresAt,
        Instant now
    ) {
        UUID id = UUID.randomUUID();
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        jdbcClient.sql("""
                INSERT INTO user_invites (
                    id, code_prefix, token_hash, role, note, created_by, expires_at, created_at
                )
                VALUES (:id, :codePrefix, :tokenHash, :role, :note, :createdBy, :expiresAt, :createdAt)
                """)
            .param("id", id)
            .param("codePrefix", codePrefix)
            .param("tokenHash", tokenHash)
            .param("role", role.name())
            .param("note", cleanNote)
            .param("createdBy", createdBy)
            .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
            .param("createdAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .update();

        return findById(id).orElseThrow(() -> new IllegalStateException("Created invite could not be loaded"));
    }

    public Optional<InviteRecord> findById(UUID id) {
        return jdbcClient.sql("""
                SELECT id, code_prefix, token_hash, role, note, created_by,
                       expires_at, used_by, used_at, revoked_at, created_at
                FROM user_invites
                WHERE id = :id
                """)
            .param("id", id)
            .query((rs, rowNum) -> mapInvite(rs))
            .optional();
    }

    public Optional<InviteRecord> findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }
        return jdbcClient.sql("""
                SELECT id, code_prefix, token_hash, role, note, created_by,
                       expires_at, used_by, used_at, revoked_at, created_at
                FROM user_invites
                WHERE token_hash = :tokenHash
                """)
            .param("tokenHash", tokenHash.toLowerCase(Locale.ROOT))
            .query((rs, rowNum) -> mapInvite(rs))
            .optional();
    }

    public List<InviteRecord> findRecent(int limit) {
        return jdbcClient.sql("""
                SELECT id, code_prefix, token_hash, role, note, created_by,
                       expires_at, used_by, used_at, revoked_at, created_at
                FROM user_invites
                ORDER BY created_at DESC, id
                LIMIT :limit
                """)
            .param("limit", Math.clamp(limit, 1, 100))
            .query((rs, rowNum) -> mapInvite(rs))
            .list();
    }

    public boolean redeemAtomically(UUID id, String usedBy, Instant now) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        int updated = jdbcClient.sql("""
                UPDATE user_invites
                SET used_by = :usedBy, used_at = :usedAt
                WHERE id = :id
                  AND used_at IS NULL
                  AND revoked_at IS NULL
                  AND expires_at > :now
                """)
            .param("usedBy", usedBy)
            .param("usedAt", timestamp)
            .param("id", id)
            .param("now", timestamp)
            .update();
        return updated == 1;
    }

    public boolean revoke(UUID id, Instant now) {
        int updated = jdbcClient.sql("""
                UPDATE user_invites
                SET revoked_at = :revokedAt
                WHERE id = :id
                  AND used_at IS NULL
                  AND revoked_at IS NULL
                """)
            .param("revokedAt", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .param("id", id)
            .update();
        return updated == 1;
    }

    private static InviteRecord mapInvite(ResultSet rs) throws SQLException {
        return new InviteRecord(
            rs.getObject("id", UUID.class),
            rs.getString("code_prefix"),
            rs.getString("token_hash"),
            UserRole.fromDatabase(rs.getString("role")),
            rs.getString("note"),
            rs.getString("created_by"),
            rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
            rs.getString("used_by"),
            toInstant(rs.getObject("used_at", OffsetDateTime.class)),
            toInstant(rs.getObject("revoked_at", OffsetDateTime.class)),
            rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record InviteRecord(
        UUID id,
        String codePrefix,
        String tokenHash,
        UserRole role,
        String note,
        String createdBy,
        Instant expiresAt,
        String usedBy,
        Instant usedAt,
        Instant revokedAt,
        Instant createdAt
    ) {
        public String state(Instant now) {
            if (usedAt != null) {
                return "USED";
            }
            if (revokedAt != null) {
                return "REVOKED";
            }
            if (!expiresAt.isAfter(now)) {
                return "EXPIRED";
            }
            return "PENDING";
        }
    }
}
