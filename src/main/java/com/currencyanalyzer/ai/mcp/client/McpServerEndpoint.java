package com.currencyanalyzer.ai.mcp.client;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over a remote Model Context Protocol server connection used by {@link McpClientToolGateway}.
 */
public interface McpServerEndpoint {

    String serverName();

    String serverImplementation();

    List<DiscoveredTool> listTools();

    ToolInvocationOutcome callTool(String remoteToolName, Map<String, Object> arguments);

    record DiscoveredTool(String name, String description, String inputSchemaJson, boolean readOnlyHint) {
    }

    record ToolInvocationOutcome(boolean isError, String textContent) {
    }
}
