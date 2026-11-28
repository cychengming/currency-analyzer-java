package com.currencyanalyzer.ai.mcp.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@ConfigurationProperties(prefix = "app.ai.mcp.client")
public class McpClientGatewayProperties {

    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int MIN_CONCURRENT_CALLS = 1;
    private static final int MAX_CONCURRENT_CALLS = 32;
    private static final int MIN_RESULT_CHARS = 256;
    private static final int MAX_RESULT_CHARS = 16_000;
    private static final int MIN_FAILURE_THRESHOLD = 1;
    private static final int MAX_FAILURE_THRESHOLD = 50;
    private static final int MIN_OPEN_SECONDS = 5;
    private static final int MAX_OPEN_SECONDS = 600;
    private static final int MIN_TOOL_CACHE_TTL_SECONDS = 10;
    private static final int MAX_TOOL_CACHE_TTL_SECONDS = 3_600;

    private boolean enabled;
    private int defaultTimeoutSeconds = 10;
    private int defaultMaxConcurrentCalls = 4;
    private int defaultMaxResultChars = 4000;
    private int circuitBreakerFailureThreshold = 5;
    private int circuitBreakerOpenSeconds = 30;
    private int toolListCacheTtlSeconds = 300;
    private Map<String, ServerPolicy> servers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = clamp(defaultTimeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
    }

    public int getDefaultMaxConcurrentCalls() {
        return defaultMaxConcurrentCalls;
    }

    public void setDefaultMaxConcurrentCalls(int defaultMaxConcurrentCalls) {
        this.defaultMaxConcurrentCalls = clamp(defaultMaxConcurrentCalls, MIN_CONCURRENT_CALLS, MAX_CONCURRENT_CALLS);
    }

    public int getDefaultMaxResultChars() {
        return defaultMaxResultChars;
    }

    public void setDefaultMaxResultChars(int defaultMaxResultChars) {
        this.defaultMaxResultChars = clamp(defaultMaxResultChars, MIN_RESULT_CHARS, MAX_RESULT_CHARS);
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = clamp(
            circuitBreakerFailureThreshold, MIN_FAILURE_THRESHOLD, MAX_FAILURE_THRESHOLD
        );
    }

    public int getCircuitBreakerOpenSeconds() {
        return circuitBreakerOpenSeconds;
    }

    public void setCircuitBreakerOpenSeconds(int circuitBreakerOpenSeconds) {
        this.circuitBreakerOpenSeconds = clamp(circuitBreakerOpenSeconds, MIN_OPEN_SECONDS, MAX_OPEN_SECONDS);
    }

    public int getToolListCacheTtlSeconds() {
        return toolListCacheTtlSeconds;
    }

    public void setToolListCacheTtlSeconds(int toolListCacheTtlSeconds) {
        this.toolListCacheTtlSeconds = clamp(
            toolListCacheTtlSeconds, MIN_TOOL_CACHE_TTL_SECONDS, MAX_TOOL_CACHE_TTL_SECONDS
        );
    }

    public Map<String, ServerPolicy> getServers() {
        return servers;
    }

    public void setServers(Map<String, ServerPolicy> servers) {
        Map<String, ServerPolicy> normalized = new LinkedHashMap<>();
        if (servers != null) {
            servers.forEach((key, policy) -> {
                String cleanKey = normalizeServerKey(key);
                if (!cleanKey.isEmpty() && policy != null) {
                    normalized.put(cleanKey, policy);
                }
            });
        }
        this.servers = normalized;
    }

    public Optional<ServerPolicy> policyFor(String serverName) {
        String key = normalizeServerKey(serverName);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        ServerPolicy direct = servers.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        for (Map.Entry<String, ServerPolicy> entry : servers.entrySet()) {
            if (normalizeServerKey(entry.getKey()).equals(key)) {
                return Optional.ofNullable(entry.getValue());
            }
        }
        return Optional.empty();
    }

    static String normalizeServerKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class ServerPolicy {
        private boolean enabled;
        private List<String> allowedTools = new ArrayList<>();
        private boolean allowWriteTools;
        private Integer timeoutSeconds;
        private Integer maxConcurrentCalls;
        private Integer maxResultChars;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getAllowedTools() {
            return allowedTools;
        }

        /**
         * Stores explicit remote tool names only; wildcard {@code *} and blanks are ignored (fail-closed).
         */
        public void setAllowedTools(List<String> allowedTools) {
            List<String> sanitized = new ArrayList<>();
            if (allowedTools != null) {
                for (String tool : allowedTools) {
                    if (tool == null) {
                        continue;
                    }
                    String trimmed = tool.trim();
                    if (!trimmed.isEmpty() && !"*".equals(trimmed) && !sanitized.contains(trimmed)) {
                        sanitized.add(trimmed);
                    }
                }
            }
            this.allowedTools = sanitized;
        }

        public boolean isAllowWriteTools() {
            return allowWriteTools;
        }

        public void setAllowWriteTools(boolean allowWriteTools) {
            this.allowWriteTools = allowWriteTools;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds == null ? null : clamp(timeoutSeconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        }

        public Integer getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(Integer maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls == null
                ? null
                : clamp(maxConcurrentCalls, MIN_CONCURRENT_CALLS, MAX_CONCURRENT_CALLS);
        }

        public Integer getMaxResultChars() {
            return maxResultChars;
        }

        public void setMaxResultChars(Integer maxResultChars) {
            this.maxResultChars = maxResultChars == null
                ? null
                : clamp(maxResultChars, MIN_RESULT_CHARS, MAX_RESULT_CHARS);
        }

        public int effectiveTimeoutSeconds(int defaultVal) {
            return timeoutSeconds != null ? timeoutSeconds : clamp(defaultVal, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        }

        public int effectiveMaxConcurrentCalls(int defaultVal) {
            return maxConcurrentCalls != null
                ? maxConcurrentCalls
                : clamp(defaultVal, MIN_CONCURRENT_CALLS, MAX_CONCURRENT_CALLS);
        }

        public int effectiveMaxResultChars(int defaultVal) {
            return maxResultChars != null ? maxResultChars : clamp(defaultVal, MIN_RESULT_CHARS, MAX_RESULT_CHARS);
        }
    }
}
