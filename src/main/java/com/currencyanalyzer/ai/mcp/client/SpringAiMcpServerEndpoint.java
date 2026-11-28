package com.currencyanalyzer.ai.mcp.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@link McpServerEndpoint} adapter backed by a Spring AI {@link McpSyncClient}.
 */
public class SpringAiMcpServerEndpoint implements McpServerEndpoint {

    private static final String DEFAULT_INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpSyncClient client;
    private final String logicalServerName;

    public SpringAiMcpServerEndpoint(McpSyncClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.logicalServerName = resolveLogicalServerName(client);
    }

    @Override
    public String serverName() {
        return logicalServerName;
    }

    @Override
    public String serverImplementation() {
        McpSchema.Implementation serverInfo = safeServerInfo();
        if (serverInfo == null || serverInfo.name() == null || serverInfo.name().isBlank()) {
            return "";
        }
        String version = serverInfo.version() == null ? "" : serverInfo.version().trim();
        return version.isEmpty() ? serverInfo.name().trim() : serverInfo.name().trim() + ":" + version;
    }

    @Override
    public List<DiscoveredTool> listTools() {
        McpSchema.ListToolsResult result = client.listTools();
        if (result == null || result.tools() == null || result.tools().isEmpty()) {
            return List.of();
        }
        List<DiscoveredTool> tools = new ArrayList<>(result.tools().size());
        for (McpSchema.Tool tool : result.tools()) {
            if (tool == null || tool.name() == null || tool.name().isBlank()) {
                continue;
            }
            boolean readOnly = tool.annotations() == null || !Boolean.FALSE.equals(tool.annotations().readOnlyHint());
            tools.add(new DiscoveredTool(
                tool.name().trim(),
                tool.description() == null ? "" : tool.description().trim(),
                serializeSchema(tool.inputSchema()),
                readOnly
            ));
        }
        return List.copyOf(tools);
    }

    @Override
    public ToolInvocationOutcome callTool(String remoteToolName, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(remoteToolName, args));
        if (result == null) {
            return new ToolInvocationOutcome(false, "");
        }
        boolean isError = Boolean.TRUE.equals(result.isError());
        String combined = flattenContent(result);
        return new ToolInvocationOutcome(isError, combined);
    }

    private McpSchema.Implementation safeServerInfo() {
        try {
            return client.getServerInfo();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String resolveLogicalServerName(McpSyncClient client) {
        McpSchema.Implementation clientInfo = safeClientInfo(client);
        if (clientInfo != null && clientInfo.name() != null && !clientInfo.name().isBlank()) {
            String raw = clientInfo.name().trim();
            int sep = raw.lastIndexOf(" - ");
            String candidate = sep >= 0 && sep + 3 < raw.length() ? raw.substring(sep + 3) : raw;
            String normalized = normalizeIdentifier(candidate);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        try {
            McpSchema.Implementation serverInfo = client.getServerInfo();
            if (serverInfo != null && serverInfo.name() != null && !serverInfo.name().isBlank()) {
                String normalized = normalizeIdentifier(serverInfo.name());
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to default server name when server info is unavailable before initialization.
        }
        return "external";
    }

    private static McpSchema.Implementation safeClientInfo(McpSyncClient client) {
        try {
            return client.getClientInfo();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-') {
                out.append(ch);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    private static String serializeSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return DEFAULT_INPUT_SCHEMA;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException exception) {
            return DEFAULT_INPUT_SCHEMA;
        }
    }

    private static String flattenContent(McpSchema.CallToolResult result) {
        if (result.content() != null && !result.content().isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (McpSchema.Content item : result.content()) {
                if (item == null) {
                    continue;
                }
                String piece = item instanceof McpSchema.TextContent textContent
                    ? textContent.text()
                    : String.valueOf(item);
                if (piece == null || piece.isBlank()) {
                    continue;
                }
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(piece.strip());
            }
            if (!out.isEmpty()) {
                return out.toString();
            }
        }
        if (result.structuredContent() != null) {
            try {
                return OBJECT_MAPPER.writeValueAsString(result.structuredContent());
            } catch (JsonProcessingException ignored) {
                return String.valueOf(result.structuredContent());
            }
        }
        return "";
    }
}
