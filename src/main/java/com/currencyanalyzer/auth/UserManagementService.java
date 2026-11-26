package com.currencyanalyzer.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserManagementService {

    private static final int MIN_USERNAME_LEN = 3;
    private static final int MAX_USERNAME_LEN = 64;
    private static final int MIN_PASSWORD_LEN = 6;
    private static final int MAX_PASSWORD_LEN = 200;
    private static final int DEFAULT_INVITE_HOURS = 72;
    private static final int MAX_INVITE_HOURS = 720;

    private final UserRepository userRepository;
    private final UserInviteRepository inviteRepository;
    private final KnowledgeAdminService knowledgeAdminService;
    private final CompatiblePbkdf2PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserManagementService(
        UserRepository userRepository,
        UserInviteRepository inviteRepository,
        KnowledgeAdminService knowledgeAdminService,
        CompatiblePbkdf2PasswordEncoder passwordEncoder,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.inviteRepository = inviteRepository;
        this.knowledgeAdminService = knowledgeAdminService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        return userRepository.findAll().stream()
            .map(UserSummary::from)
            .toList();
    }

    @Transactional
    public UserSummary createUserByAdmin(String rawUsername, String rawPassword, String displayName, UserRole role) {
        String username = validateUsername(rawUsername);
        String password = validatePassword(rawPassword);
        UserRole targetRole = role == null ? UserRole.MEMBER : role;
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateUsernameException("Username already exists");
        }
        String cleanDisplay = sanitizeOptionalText(displayName, 120);
        try {
            UserAccount created = userRepository.create(username, passwordEncoder.encode(password), targetRole, cleanDisplay);
            return UserSummary.from(created);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateUsernameException("Username already exists");
        }
    }

    @Transactional
    public UserSummary updateUserByAdmin(
        String actorUsername,
        long targetUserId,
        UserRole newRole,
        String rawStatus,
        String displayName
    ) {
        UserAccount target = userRepository.findById(targetUserId)
            .orElseThrow(() -> new UserNotFoundException("User not found"));

        UserRole effectiveNewRole = newRole == null ? target.role() : newRole;
        String effectiveStatus = parseStatus(rawStatus, target.status());
        String effectiveDisplay = displayName != null
            ? sanitizeOptionalText(displayName, 120)
            : target.displayName();

        boolean actingOnSelf = actorUsername != null && actorUsername.trim().equalsIgnoreCase(target.username());
        if (actingOnSelf && (!"ACTIVE".equals(effectiveStatus) || effectiveNewRole != UserRole.ADMIN)) {
            throw new IllegalArgumentException("You cannot disable or demote your own administrator account");
        }

        boolean wasActiveAdmin = target.isActive() && target.role() == UserRole.ADMIN;
        boolean remainsActiveAdmin = "ACTIVE".equals(effectiveStatus) && effectiveNewRole == UserRole.ADMIN;
        if (wasActiveAdmin && !remainsActiveAdmin && userRepository.countActiveAdmins() <= 1) {
            throw new IllegalArgumentException("At least one active ADMIN account must remain");
        }

        userRepository.updateRoleStatusAndDisplayName(targetUserId, effectiveNewRole, effectiveStatus, effectiveDisplay);
        return userRepository.findById(targetUserId)
            .map(UserSummary::from)
            .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    @Transactional
    public void resetPasswordByAdmin(long targetUserId, String rawNewPassword) {
        if (userRepository.findById(targetUserId).isEmpty()) {
            throw new UserNotFoundException("User not found");
        }
        String password = validatePassword(rawNewPassword);
        userRepository.updatePasswordHash(targetUserId, passwordEncoder.encode(password));
    }

    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.isEmpty()) {
            throw new IllegalArgumentException("Current password is required");
        }
        String validatedNew = validatePassword(newPassword);
        UserAccount account = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException("User account not found"));
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        userRepository.updatePasswordHash(account.id(), passwordEncoder.encode(validatedNew));
    }

    @Transactional
    public CreatedInviteResponse createInvite(String createdBy, UserRole role, Integer expiresInHours, String note) {
        UserRole targetRole = role == null ? UserRole.MEMBER : role;
        int hours = expiresInHours == null ? DEFAULT_INVITE_HOURS : expiresInHours;
        if (hours < 1 || hours > MAX_INVITE_HOURS) {
            throw new IllegalArgumentException("expires_in_hours must be between 1 and " + MAX_INVITE_HOURS);
        }
        String rawCode = UserInviteRepository.generateInviteCode();
        String normalized = UserInviteRepository.normalizeCode(rawCode);
        String tokenHash = UserInviteRepository.sha256Hex(normalized);
        String codePrefix = rawCode.length() > 10 ? rawCode.substring(0, 10) : rawCode;
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofHours(hours));
        String cleanNote = sanitizeOptionalText(note, 255);

        UserInviteRepository.InviteRecord saved = inviteRepository.insert(
            codePrefix,
            tokenHash,
            targetRole,
            cleanNote,
            createdBy,
            expiresAt,
            now
        );
        return new CreatedInviteResponse(InviteView.from(saved, now), rawCode);
    }

    @Transactional(readOnly = true)
    public List<InviteView> listInvites() {
        Instant now = clock.instant();
        return inviteRepository.findRecent(50).stream()
            .map(record -> InviteView.from(record, now))
            .toList();
    }

    @Transactional
    public boolean revokeInvite(UUID id) {
        return inviteRepository.revoke(id, clock.instant());
    }

    @Transactional
    public UserAccount registerUser(
        String rawUsername,
        String rawPassword,
        String displayName,
        String inviteCode,
        boolean publicRegistrationEnabled
    ) {
        boolean hasInvite = inviteCode != null && !inviteCode.isBlank();
        if (!publicRegistrationEnabled && !hasInvite) {
            throw new RegistrationDisabledException("Registration is disabled");
        }

        String username = validateUsernameForRegistration(rawUsername);
        String password = validatePassword(rawPassword);

        if (knowledgeAdminService.isReservedAdminName(username)) {
            throw new ReservedUsernameException("This username is reserved and cannot be registered");
        }
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateUsernameException("Username already exists");
        }

        UserRole assignedRole = resolveRegistrationRole(hasInvite, inviteCode, username);
        String cleanDisplay = sanitizeOptionalText(displayName, 120);
        try {
            return userRepository.create(username, passwordEncoder.encode(password), assignedRole, cleanDisplay);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateUsernameException("Username already exists");
        }
    }

    private UserRole resolveRegistrationRole(boolean hasInvite, String rawInviteCode, String username) {
        if (!hasInvite) {
            boolean promoteFirstOwner = userRepository.countAll() == 0 && !knowledgeAdminService.hasConfiguredAdmins();
            return promoteFirstOwner ? UserRole.ADMIN : UserRole.MEMBER;
        }
        String normalized = UserInviteRepository.normalizeCode(rawInviteCode);
        if (normalized.length() < 8) {
            throw new IllegalArgumentException("Invalid or expired invite code");
        }
        String tokenHash = UserInviteRepository.sha256Hex(normalized);
        UserInviteRepository.InviteRecord invite = inviteRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new IllegalArgumentException("Invalid or expired invite code"));
        if (!inviteRepository.redeemAtomically(invite.id(), username, clock.instant())) {
            throw new IllegalArgumentException("Invite code has already been used, revoked, or expired");
        }
        return invite.role();
    }

    private static String validateUsername(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (username.length() < MIN_USERNAME_LEN) {
            throw new IllegalArgumentException("Username must be at least 3 characters");
        }
        if (username.length() > MAX_USERNAME_LEN) {
            throw new IllegalArgumentException("Username must not exceed " + MAX_USERNAME_LEN + " characters");
        }
        return username;
    }

    private static String validateUsernameForRegistration(String rawUsername) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("Username and password required");
        }
        return validateUsername(username);
    }

    private static String validatePassword(String rawPassword) {
        String password = rawPassword == null ? "" : rawPassword.trim();
        if (password.length() < MIN_PASSWORD_LEN) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }
        if (password.length() > MAX_PASSWORD_LEN) {
            throw new IllegalArgumentException("Password must not exceed " + MAX_PASSWORD_LEN + " characters");
        }
        return password;
    }

    private static String parseStatus(String rawStatus, String fallback) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return fallback == null || fallback.isBlank() ? "ACTIVE" : fallback;
        }
        String upper = rawStatus.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(upper) && !"DISABLED".equals(upper)) {
            throw new IllegalArgumentException("status must be ACTIVE or DISABLED");
        }
        return upper;
    }

    private static String sanitizeOptionalText(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }

    public record UserSummary(
        long id,
        String username,
        @JsonProperty("display_name") String displayName,
        UserRole role,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("last_login_at") Instant lastLoginAt
    ) {
        static UserSummary from(UserAccount account) {
            return new UserSummary(
                account.id(),
                account.username(),
                account.displayName(),
                account.role(),
                account.status(),
                account.createdAt(),
                account.lastLoginAt()
            );
        }
    }

    public record InviteView(
        UUID id,
        @JsonProperty("code_prefix") String codePrefix,
        UserRole role,
        String note,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("used_by") String usedBy,
        @JsonProperty("used_at") Instant usedAt,
        @JsonProperty("revoked_at") Instant revokedAt,
        @JsonProperty("created_at") Instant createdAt,
        String state
    ) {
        static InviteView from(UserInviteRepository.InviteRecord record, Instant now) {
            return new InviteView(
                record.id(),
                record.codePrefix(),
                record.role(),
                record.note(),
                record.createdBy(),
                record.expiresAt(),
                record.usedBy(),
                record.usedAt(),
                record.revokedAt(),
                record.createdAt(),
                record.state(now)
            );
        }
    }

    public record CreatedInviteResponse(
        InviteView invite,
        @JsonProperty("invite_code") String inviteCode
    ) {
    }

    public static final class DuplicateUsernameException extends RuntimeException {
        public DuplicateUsernameException(String message) {
            super(message);
        }
    }

    public static final class RegistrationDisabledException extends RuntimeException {
        public RegistrationDisabledException(String message) {
            super(message);
        }
    }

    public static final class ReservedUsernameException extends RuntimeException {
        public ReservedUsernameException(String message) {
            super(message);
        }
    }

    public static final class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String message) {
            super(message);
        }
    }
}
