package com.currencyanalyzer.ai.mcp.client;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CurrencyAnalyzerApplication.class)
@AutoConfigureMockMvc
class McpClientAdminControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void enforcesAuthenticationCsrfAndAdminRoleOnMcpGovernanceEndpoints() throws Exception {
        mockMvc.perform(get("/api/ai/mcp/servers"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/ai/mcp/servers").with(user("alice").roles("MEMBER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/ai/mcp/servers").with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/ai/mcp/servers/refresh").with(user("admin")))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/ai/mcp/servers/refresh").with(user("alice").roles("MEMBER")).with(csrf()))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/ai/mcp/servers").with(user("admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.servers").isArray());

        mockMvc.perform(post("/api/ai/mcp/servers/refresh").with(user("admin")).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.servers").isArray());
    }
}
