package com.currencyanalyzer.ai.mcp.client;

import com.currencyanalyzer.ai.chat.AiMetrics;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            null,
            Map.of(),
            null,
            null,
            null
        );
        McpSchema.Tool explicitFalseTool = new McpSchema.Tool(
            "mutate_note",
            null,
            "Mutate",
            Map.of(),
            null,
            new McpSchema.ToolAnnotations(null, false, true, false, false, false),
            null
        );
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(
            Arrays.asList(readTool, writeTool, explicitFalseTool, null),
            null
        ));
        McpSchema.Content nonText = mock(McpSchema.Content.class);
        when(nonText.toString()).thenReturn("BinarySummary");
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
            new McpSchema.CallToolResult(
                Arrays.asList(new McpSchema.TextContent("Line 1"), null, new McpSchema.TextContent(" "), nonText),
                false,
                null,
                null
            )
        );

        SpringAiMcpServerEndpoint endpoint = new SpringAiMcpServerEndpoint(client);
        assertEquals("fred", endpoint.serverName());
        assertEquals("fred-server:2.1.0", endpoint.serverImplementation());

        List<McpServerEndpoint.DiscoveredTool> tools = endpoint.listTools();
        assertEquals(3, tools.size());
        assertEquals("get_cpi", tools.get(0).name());
        assertTrue(tools.get(0).readOnlyHint());
        assertTrue(tools.get(0).inputSchemaJson().contains("\"country\""));
        assertEquals("publish_note", tools.get(1).name());
        assertTrue(tools.get(1).readOnlyHint());
        assertEquals("", tools.get(1).description());
        assertEquals("{\"type\":\"object\",\"properties\":{}}", tools.get(1).inputSchemaJson());
        assertFalse(tools.get(2).readOnlyHint());

        McpServerEndpoint.ToolInvocationOutcome outcome = endpoint.callTool("get_cpi", Map.of("country", "US"));
        assertFalse(outcome.isError());
        assertEquals("Line 1\nBinarySummary", outcome.textContent());

        ArgumentCaptor<McpSchema.CallToolRequest> requestCaptor = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(requestCaptor.capture());
        assertEquals("get_cpi", requestCaptor.getValue().name());
        assertEquals(Map.of("country", "US"), requestCaptor.getValue().arguments());
    }

    @Test
    void fallsBackCleanlyForMissingServerInfoNullResultsAndBlankNames() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(null);
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("Macro Feed Server", ""));
        when(client.listTools()).thenReturn(null);
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(
            new McpSchema.CallToolResult(List.of(), true, Map.of("reason", "rate_limited"), null)
        );

        SpringAiMcpServerEndpoint endpoint = new SpringAiMcpServerEndpoint(client);
        assertEquals("macro_feed_server", endpoint.serverName());
        assertEquals("Macro Feed Server", endpoint.serverImplementation());
        assertTrue(endpoint.listTools().isEmpty());

        McpServerEndpoint.ToolInvocationOutcome outcome = endpoint.callTool("ping", null);
        assertTrue(outcome.isError());
        assertTrue(outcome.textContent().contains("rate_limited"));

        McpSyncClient uninitializedClient = mock(McpSyncClient.class);
        when(uninitializedClient.getClientInfo()).thenThrow(new IllegalStateException("not ready"));
        when(uninitializedClient.getServerInfo()).thenThrow(new IllegalStateException("not ready"));
        when(uninitializedClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
        when(uninitializedClient.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(null);

        SpringAiMcpServerEndpoint fallback = new SpringAiMcpServerEndpoint(uninitializedClient);
        assertEquals("external", fallback.serverName());
        assertEquals("", fallback.serverImplementation());
        assertTrue(fallback.listTools().isEmpty());
        assertEquals(new McpServerEndpoint.ToolInvocationOutcome(false, ""), fallback.callTool("noop", Map.of()));
    }

    @Test
    void clampsGatewayPropertiesAndBuildsGatewayFromConfiguration() {
        McpClientGatewayProperties props = new McpClientGatewayProperties();
        props.setDefaultTimeoutSeconds(999);
        props.setDefaultMaxConcurrentCalls(0);
        props.setDefaultMaxResultChars(10);
        props.setCircuitBreakerFailureThreshold(100);
        props.setCircuitBreakerOpenSeconds(1);
        props.setToolListCacheTtlSeconds(99999);

        assertEquals(60, props.getDefaultTimeoutSeconds());
        assertEquals(1, props.getDefaultMaxConcurrentCalls());
        assertEquals(256, props.getDefaultMaxResultChars());
        assertEquals(50, props.getCircuitBreakerFailureThreshold());
        assertEquals(5, props.getCircuitBreakerOpenSeconds());
        assertEquals(3600, props.getToolListCacheTtlSeconds());

        McpClientGatewayProperties.ServerPolicy policy = new McpClientGatewayProperties.ServerPolicy();
        policy.setTimeoutSeconds(120);
        policy.setMaxConcurrentCalls(64);
        policy.setMaxResultChars(20_000);
        policy.setAllowedTools(Arrays.asList(" read_one ", null, "*", "read_one"));
        assertEquals(60, policy.getTimeoutSeconds());
        assertEquals(32, policy.getMaxConcurrentCalls());
        assertEquals(16_000, policy.getMaxResultChars());
        assertEquals(List.of("read_one"), policy.getAllowedTools());

        McpClientGatewayProperties.ServerPolicy defaults = new McpClientGatewayProperties.ServerPolicy();
        assertNull(defaults.getTimeoutSeconds());
        assertNull(defaults.getMaxConcurrentCalls());
        assertNull(defaults.getMaxResultChars());
        assertEquals(15, defaults.effectiveTimeoutSeconds(15));
        assertEquals(3, defaults.effectiveMaxConcurrentCalls(3));
        assertEquals(2048, defaults.effectiveMaxResultChars(2048));

        Map<String, McpClientGatewayProperties.ServerPolicy> rawServers = new LinkedHashMap<>();
        rawServers.put("  FRED ", policy);
        rawServers.put(" ", defaults);
        rawServers.put("null_val", null);
        props.setServers(rawServers);
        assertEquals(1, props.getServers().size());
        assertTrue(props.policyFor("fred").isPresent());
        assertTrue(props.policyFor("").isEmpty());
        assertTrue(props.policyFor("missing").isEmpty());

        McpSyncClient client = mock(McpSyncClient.class);
        when(client.getClientInfo()).thenReturn(new McpSchema.Implementation("app - fred", "1.0"));
        when(client.getServerInfo()).thenReturn(new McpSchema.Implementation("fred", "1.0"));
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));

        @SuppressWarnings("unchecked")
        ObjectProvider<List<McpSyncClient>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(Arrays.asList(client, null));

        McpClientGatewayConfiguration config = new McpClientGatewayConfiguration();
        props.setEnabled(true);
        policy.setEnabled(true);
        McpClientToolGateway gateway = config.mcpClientToolGateway(provider, props, AiMetrics.noop(), Clock.systemUTC());
        try {
            assertTrue(gateway.isEnabled());
            assertEquals(1, gateway.serverStatuses().size());
            assertEquals("fred", gateway.serverStatuses().getFirst().serverName());
        } finally {
            gateway.shutdown();
        }
    }
}
