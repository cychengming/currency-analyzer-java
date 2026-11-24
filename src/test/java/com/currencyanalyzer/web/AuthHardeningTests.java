package com.currencyanalyzer.web;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "app.auth.registration-enabled=false",
        "app.auth.login-max-attempts=3",
        "app.auth.login-window-seconds=60"
    }
)
@AutoConfigureMockMvc
class AuthHardeningTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsAndBlocksPublicRegistrationWhenDisabled() throws Exception {
        mockMvc.perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registration_enabled").value(false));

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username": "stranger", "password": "strong-password"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Registration is disabled"));
    }

    @Test
    void rateLimitsRepeatedFailedLoginsWithTooManyRequests() throws Exception {
        String badCredentials = """
            {"username": "nobody", "password": "wrong-password"}
            """;

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/auth/login")
                    .contentType("application/json")
                    .content(badCredentials))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        }

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(badCredentials))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error", containsString("Too many failed login attempts")));
    }
}
