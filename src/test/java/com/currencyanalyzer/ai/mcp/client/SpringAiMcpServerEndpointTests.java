package com.currencyanalyzer.ai.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiMcpServerEndpointTests {

    @Test
    void extractsLogicalNameMapsToolsAndExecutesCalls() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("currency-analyzer-java - fred", "1.0.0"));
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("fred-server", "2.1.0"));

        McpSchema.Tool readTool = new McpSchema.Tool(
            "get_cpi",
            null,
            "Fetch CPI",
            Map.of("type", "object", "properties", Map.of("country", Map.of("type", "string"))),
            null,
            new McpSchema.ToolAnnotations(null, true, false, true, false, false),
            null
        );
        McpSchema.Tool writeTool = new McpSchema.Tool(
            "publish_note",
            null,
            "Write note",
            Map.of(),
            null,
            new McpSchema.ToolAnnotations(null, false, true, false, false, false),
            null
        );
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(readTool, writeTool), null));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
            new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Line 1"), new McpSchema.TextContent("Line 2")),
                false,
                null,
                null
            )
        );

        SpringAiMcpServerEndpoint endpoint = new SpringAiMcpServerEndpoint(client);
        assertEquals("fred", endpoint.serverName());
        assertEquals("fred-server:2.1.0", endpoint.serverImplementation());

        List<McpServerEndpoint.DiscoveredTool> tools = endpoint.listTools();
        assertEquals(2, tools.size());
        assertEquals("get_cpi", tools.get(0).name());
        assertTrue(tools.get(0).readOnlyHint());
        assertTrue(tools.get(0).inputSchemaJson().contains("\"country\""));
        assertEquals("publish_note", tools.get(1).name());
        assertFalse(tools.get(1).readOnlyHint());
        assertEquals("{\"type\":\"object\",\"properties\":{}}", tools.get(1).inputSchemaJson());

        McpServerEndpoint.ToolInvocationOutcome outcome = endpoint.callTool("get_cpi", Map.of("country", "US"));
        assertFalse(outcome.isError());
        assertEquals("Line 1\nLine 2", outcome.textContent());

        ArgumentCaptor<McpSchema.CallToolRequest> requestCaptor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(requestCaptor.capture());
        assertEquals("get_cpi", requestCaptor.getValue().name());
        assertEquals(Map.of("country", "US"), requestCaptor.getValue().arguments());
    }

    @Test
    void fallsBackToServerInfoAndStructuredContentError() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(null);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("Macro Feed Server", "1.0"));
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
            new McpSchema.CallToolResult(List.of(), true, Map.of("reason", "rate_limited"), null)
        );

        SpringAiMcpServerEndpoint endpoint = new SpringAiMcpServerEndpoint(client);
        assertEquals("macro_feed_server", endpoint.serverName());

        McpServerEndpoint.ToolInvocationOutcome outcome = endpoint.callTool("ping", null);
        assertTrue(outcome.isError());
        assertTrue(outcome.textContent().contains("rate_limited"));
    }
}
