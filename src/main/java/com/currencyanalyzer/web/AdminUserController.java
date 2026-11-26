package com.currencyanalyzer.web;

import com.currencyanalyzer.auth.RoleGuard;
import com.currencyanalyzer.auth.UserManagementService;
import com.currencyanalyzer.auth.UserManagementService.CreatedInviteResponse;
import com.currencyanalyzer.auth.UserManagementService.InviteView;
import com.currencyanalyzer.auth.UserManagementService.UserNotFoundException;
import com.currencyanalyzer.auth.UserManagementService.UserSummary;
import com.currencyanalyzer.auth.UserRole;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final UserManagementService userManagementService;
    private final RoleGuard roleGuard;

    public AdminUserController(UserManagementService userManagementService, RoleGuard roleGuard) {
        this.userManagementService = userManagementService;
        this.roleGuard = roleGuard;
    }

    @GetMapping("/users")
    public List<UserSummary> listUsers(Authentication authentication) {
        roleGuard.requireAdmin(authentication);
        return userManagementService.listUsers();
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(
        Authentication authentication,
        @RequestBody(required = false) CreateUserRequest request
    ) {
        roleGuard.requireAdmin(authentication);
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        UserRole role = request.role() == null || request.role().isBlank()
            ? UserRole.MEMBER
            : UserRole.parse(request.role());
        try {
            UserSummary created = userManagementService.createUserByAdmin(
                request.username(),
                request.password(),
                request.displayName(),
                role
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (UserManagementService.DuplicateUsernameException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false,
                "error", exception.getMessage()
            ));
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(
        Authentication authentication,
        @PathVariable long id,
        @RequestBody(required = false) UpdateUserRequest request
    ) {
        roleGuard.requireAdmin(authentication);
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        UserRole role = request.role() == null || request.role().isBlank()
            ? null
            : UserRole.parse(request.role());
        try {
            UserSummary updated = userManagementService.updateUserByAdmin(
                authentication.getName(),
                id,
                role,
                request.status(),
                request.displayName()
            );
            return ResponseEntity.ok(updated);
        } catch (UserNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "error", exception.getMessage()
            ));
        }
    }

    @PostMapping("/users/{id}/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(
        Authentication authentication,
        @PathVariable long id,
        @RequestBody(required = false) ResetPasswordRequest request
    ) {
        roleGuard.requireAdmin(authentication);
        try {
            userManagementService.resetPasswordByAdmin(id, request == null ? null : request.password());
            return ResponseEntity.ok(Map.of("success", true, "message", "Password reset"));
        } catch (UserNotFoundException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "error", exception.getMessage()
            ));
        }
    }

    @GetMapping("/invites")
    public List<InviteView> listInvites(Authentication authentication) {
        roleGuard.requireAdmin(authentication);
        return userManagementService.listInvites();
    }

    @PostMapping("/invites")
    public ResponseEntity<CreatedInviteResponse> createInvite(
        Authentication authentication,
        @RequestBody(required = false) CreateInviteRequest request
    ) {
        roleGuard.requireAdmin(authentication);
        UserRole role = request == null || request.role() == null || request.role().isBlank()
            ? UserRole.MEMBER
            : UserRole.parse(request.role());
        CreatedInviteResponse created = userManagementService.createInvite(
            authentication.getName(),
            role,
            request == null ? null : request.expiresInHours(),
            request == null ? null : request.note()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/invites/{id}")
    public ResponseEntity<Map<String, Object>> revokeInvite(
        Authentication authentication,
        @PathVariable UUID id
    ) {
        roleGuard.requireAdmin(authentication);
        if (!userManagementService.revokeInvite(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "error", "Active invite not found"
            ));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    public record CreateUserRequest(
        String username,
        String password,
        @JsonProperty("display_name") String displayName,
        String role
    ) {
    }

    public record UpdateUserRequest(
        String role,
        String status,
        @JsonProperty("display_name") String displayName
    ) {
    }

    public record ResetPasswordRequest(String password) {
    }

    public record CreateInviteRequest(
        String role,
        @JsonProperty("expires_in_hours") Integer expiresInHours,
        String note
    ) {
    }
}
