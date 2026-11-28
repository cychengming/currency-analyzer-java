package com.currencyanalyzer.ai.mcp.client;

import com.currencyanalyzer.auth.RoleGuard;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only governance endpoint exposing MCP server connection health, circuit breaker states,
 * and active vs. blocked tool lists (never exposing upstream URLs, headers, or credentials).
 */
@RestController
@RequestMapping("/api/ai/mcp")
public class McpClientAdminController {

    private final McpClientToolGateway gateway;
    private final RoleGuard roleGuard;

    public McpClientAdminController(McpClientToolGateway gateway, RoleGuard roleGuard) {
        this.gateway = gateway;
        this.roleGuard = roleGuard;
    }

    @GetMapping("/servers")
    public Map<String, Object> listServers(Authentication authentication) {
        roleGuard.requireAdmin(authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", gateway.isEnabled());
        body.put("servers", gateway.serverStatuses());
        return body;
    }

    @PostMapping("/servers/refresh")
    public Map<String, Object> refreshServers(Authentication authentication) {
        roleGuard.requireAdmin(authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", gateway.isEnabled());
        body.put("servers", gateway.refreshTools());
        return body;
    }
}
