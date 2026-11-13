package com.currencyanalyzer.web;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CurrencyAnalyzerApplication.class)
@AutoConfigureMockMvc
class AuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectsApisForAnonymousUsers() throws Exception {
        mockMvc.perform(get("/static/styles.css"))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"));

        mockMvc.perform(get("/api/ai/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/live-rates"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Not authenticated"));
    }

    @Test
    void registersLogsInChecksStatusAndLogsOut() throws Exception {
        String credentials = """
            {
              "username": "migration-user",
              "password": "secure-password"
            }
            """;

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(credentials))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Registration successful"));

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(credentials))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/auth/status").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logged_in").value(true))
            .andExpect(jsonPath("$.username").value("migration-user"));

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
