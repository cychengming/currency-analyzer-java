package com.currencyanalyzer.alerts;

import com.currencyanalyzer.alerts.AlertMonitoringRepository.AlertEntry;
import com.currencyanalyzer.auth.KnowledgeAdminService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AlertMonitoringController {

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_ACTIVE = "active";

    private final AlertMonitoringService service;
    private final KnowledgeAdminService knowledgeAdminService;

    public AlertMonitoringController(
        AlertMonitoringService service,
        KnowledgeAdminService knowledgeAdminService
    ) {
        this.service = service;
        this.knowledgeAdminService = knowledgeAdminService;
    }

    @GetMapping("/alerts")
    public List<AlertEntry> listAlerts() {
        return service.listAlerts();
    }

    @DeleteMapping("/alerts/clear")
    public Map<String, Object> clearAlerts(Authentication authentication) {
        requireAdmin(authentication);
        service.clearAlerts();
        return Map.of(KEY_SUCCESS, true);
    }

    @GetMapping("/alerts/conditions")
    public Map<String, Object> alertConditions() {
        return service.getAlertConditions();
    }

    @GetMapping("/alerts/preferences")
    public Map<String, Map<String, Object>> alertPreferences() {
        return service.getAlertPreferences();
    }

    @PostMapping("/alerts/preferences")
    public Map<String, Object> saveAlertPreference(
        Authentication authentication,
        @RequestBody(required = false) Map<String, Object> payload
    ) {
        requireAdmin(authentication);
        service.saveAlertPreference(payload);
        return Map.of(KEY_SUCCESS, true);
    }

    @GetMapping("/monitoring/status")
    public Map<String, Object> monitoringStatus() {
        return Map.of(KEY_ACTIVE, service.isMonitoringActive());
    }

    @PostMapping("/monitoring/start")
    public Map<String, Object> startMonitoring(Authentication authentication) {
        requireAdmin(authentication);
        service.setMonitoringActive(true);
        return Map.of(KEY_SUCCESS, true, KEY_ACTIVE, true);
    }

    @PostMapping("/monitoring/stop")
    public Map<String, Object> stopMonitoring(Authentication authentication) {
        requireAdmin(authentication);
        service.setMonitoringActive(false);
        return Map.of(KEY_SUCCESS, true, KEY_ACTIVE, false);
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        return service.getSettings();
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(
        Authentication authentication,
        @RequestBody(required = false) Map<String, Object> payload
    ) {
        requireAdmin(authentication);
        service.saveSettings(payload);
        return Map.of(KEY_SUCCESS, true);
    }

    private void requireAdmin(Authentication authentication) {
        if (!isAdmin(authentication)) {
            throw new AccessDeniedException("Admin privileges are required to modify alert or monitoring settings");
        }
    }

    private boolean isAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean hasAdminRole = authentication.getAuthorities() != null
            && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (hasAdminRole) {
            return true;
        }
        return knowledgeAdminService.isAdminUsername(authentication.getName());
    }
}
