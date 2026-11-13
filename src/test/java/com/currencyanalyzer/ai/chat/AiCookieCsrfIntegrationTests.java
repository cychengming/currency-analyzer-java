package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = "app.ai.enabled=true"
)
@AutoConfigureMockMvc
class AiCookieCsrfIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsCookieCsrfTokenForAuthenticatedConversationCreation() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk())
            .andReturn();
        String token = JsonMapper.builder().build()
            .readTree(csrfResult.getResponse().getContentAsString())
            .get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrfCookie);
        assertEquals(token, csrfCookie.getValue());

        mockMvc.perform(post("/api/ai/conversations")
                .with(user("csrf-user"))
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", token))
            .andExpect(status().isCreated());
    }
}
