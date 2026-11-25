package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.UserAlertRuleService.AlertInboxResponse;
import com.currencyanalyzer.alerts.UserAlertRuleService.AlertRuleView;
import com.currencyanalyzer.alerts.UserAlertRuleService.ProfileRequest;
import com.currencyanalyzer.alerts.UserAlertRuleService.RuleRequest;
import com.currencyanalyzer.alerts.UserAlertRuleService.UserAlertProfile;
import com.currencyanalyzer.auth.UserAccount;
import com.currencyanalyzer.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
@RequestMapping("/api")
public class UserAlertRuleController {

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_ERROR = "error";

    private final UserAlertRuleService ruleService;
    private final AlertNotificationService notificationService;
    private final UserRepository userRepository;

    public UserAlertRuleController(
        UserAlertRuleService ruleService,
        AlertNotificationService notificationService,
        UserRepository userRepository
    ) {
        this.ruleService = ruleService;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @GetMapping("/alert-rules")
    public List<AlertRuleView> listRules(Authentication authentication) {
        return ruleService.listRules(requireUserId(authentication));
    }

    @PostMapping("/alert-rules")
    public ResponseEntity<AlertRuleView> createRule(
        Authentication authentication,
        @RequestBody(required = false) RuleRequest request
    ) {
        AlertRuleView created = ruleService.createRule(requireUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/alert-rules/{id}")
    public ResponseEntity<Object> updateRule(
        Authentication authentication,
        @PathVariable UUID id,
        @RequestBody(required = false) RuleRequest request
    ) {
        return ruleService.updateRule(requireUserId(authentication), id, request)
            .<ResponseEntity<Object>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(KEY_SUCCESS, false, KEY_ERROR, "Alert rule not found")));
    }

    @DeleteMapping("/alert-rules/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(
        Authentication authentication,
        @PathVariable UUID id
    ) {
        boolean deleted = ruleService.deleteRule(requireUserId(authentication), id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(KEY_SUCCESS, false, KEY_ERROR, "Alert rule not found"));
        }
        return ResponseEntity.ok(Map.of(KEY_SUCCESS, true));
    }

    @GetMapping("/alert-inbox")
    public AlertInboxResponse getInbox(Authentication authentication) {
        return ruleService.getInbox(requireUserId(authentication));
    }

    @PostMapping("/alert-inbox/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(
        Authentication authentication,
        @PathVariable long id
    ) {
        boolean updated = ruleService.markRead(requireUserId(authentication), id);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(KEY_SUCCESS, false, KEY_ERROR, "Alert not found"));
        }
        return ResponseEntity.ok(Map.of(KEY_SUCCESS, true));
    }

    @PostMapping("/alert-inbox/read-all")
    public Map<String, Object> markAllRead(Authentication authentication) {
        int updated = ruleService.markAllRead(requireUserId(authentication));
        return Map.of(KEY_SUCCESS, true, "updated", updated);
    }

    @DeleteMapping("/alert-inbox/clear")
    public Map<String, Object> clearInbox(Authentication authentication) {
        int deleted = ruleService.clearInbox(requireUserId(authentication));
        return Map.of(KEY_SUCCESS, true, "deleted", deleted);
    }

    @GetMapping("/alert-inbox/profile")
    public UserAlertProfile getProfile(Authentication authentication) {
        return ruleService.getProfile(requireUserId(authentication));
    }

    @PutMapping("/alert-inbox/profile")
    public UserAlertProfile updateProfile(
        Authentication authentication,
        @RequestBody(required = false) ProfileRequest request
    ) {
        return ruleService.updateProfile(requireUserId(authentication), request);
    }

    @PostMapping("/alert-inbox/test-email")
    public Map<String, Object> sendPersonalTestEmail(Authentication authentication) {
        return notificationService.sendUserTestEmail(requireUserId(authentication));
    }

    private long requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        return userRepository.findByUsername(authentication.getName())
            .map(UserAccount::id)
            .orElseThrow(() -> new AccessDeniedException("Authenticated user account not found"));
    }
}
