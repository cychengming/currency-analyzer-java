package com.currencyanalyzer.ai.mcp.client;

import com.currencyanalyzer.ai.chat.AiMetrics;
import com.currencyanalyzer.ai.tools.AssistantTools;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Registers the {@link McpClientToolGateway} using a single factory method so external
 * MCP connections are centrally governed and never auto-injected unguarded into AskAI.
 */
@Configuration
@EnableConfigurationProperties(McpClientGatewayProperties.class)
public class McpClientGatewayConfiguration {

    private static final Set<String> RESERVED_BUILTIN_TOOL_NAMES = Set.of(
        AssistantTools.GET_LIVE_RATES,
        AssistantTools.GET_HISTORICAL_SERIES,
        AssistantTools.COMPARE_PAIRS,
        AssistantTools.ANALYZE_PAIR_TECHNICALS,
        AssistantTools.CALCULATE_POSITION_SIZE,
        AssistantTools.GET_SETTINGS,
        AssistantTools.SEARCH_NOTES,
        AssistantTools.LIST_MY_ALERT_RULES,
        AssistantTools.GET_MY_ALERT_INBOX,
        AssistantTools.CREATE_ALERT_RULE,
        AssistantTools.UPDATE_ALERT_RULE_STATUS
    );

    @Bean
    public McpClientToolGateway mcpClientToolGateway(
        ObjectProvider<List<McpSyncClient>> clientsProvider,
        McpClientGatewayProperties properties,
        AiMetrics aiMetrics,
        Clock clock
    ) {
        List<McpSyncClient> clients = clientsProvider.getIfAvailable(List::of);
        List<McpServerEndpoint> endpoints = new ArrayList<>(clients.size());
        for (McpSyncClient client : clients) {
            if (client != null) {
                endpoints.add(new SpringAiMcpServerEndpoint(client));
            }
        }
        return new McpClientToolGateway(
            endpoints,
            properties,
            aiMetrics,
            clock,
            RESERVED_BUILTIN_TOOL_NAMES
        );
    }
}
