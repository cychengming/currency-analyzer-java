package com.currencyanalyzer.ai.chat;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.ai.provider.AiProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = "app.ai.enabled=true"
)
@AutoConfigureMockMvc
@Import(AiConversationControllerTests.FakeProviderConfiguration.class)
class AiConversationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createsListsAndDeletesOnlyOwnedConversations() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("alice"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.created_at").isNotEmpty())
            .andReturn();

        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        mockMvc.perform(get("/api/ai/conversations").with(user("bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(delete("/api/ai/conversations/{id}", id)
                .with(user("bob"))
                .with(csrf()))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/ai/conversations").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(id));

        mockMvc.perform(delete("/api/ai/conversations/{id}", id)
                .with(user("alice"))
                .with(csrf()))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/conversations").with(user("alice")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void rejectsConversationListLimitsOutsideBounds() throws Exception {
        mockMvc.perform(get("/api/ai/conversations")
                .param("limit", "101")
                .with(user("alice")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("limit must be between 1 and 100"));
    }

    @Test
    void rejectsOversizedMessagesBeforeStartingProviderCall() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("limit-user"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();
        String oversizedMessage = "x".repeat(8001);

        mockMvc.perform(post("/api/ai/conversations/{id}/messages", id)
                .with(user("limit-user"))
                .with(csrf())
                .contentType("application/json")
                .content(JsonMapper.builder().build().createObjectNode()
                    .put("message", oversizedMessage).toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("message exceeds the maximum length of 8000"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/ai/conversations"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void streamsAndPersistsAssistantResponse() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/ai/conversations")
                .with(user("stream-user"))
                .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn();
        String id = JsonMapper.builder().build()
            .readTree(created.getResponse().getContentAsString())
            .get("id").asText();

        MvcResult pendingResponse = mockMvc.perform(post("/api/ai/conversations/{id}/messages", id)
                .with(user("stream-user"))
                .with(csrf())
                .contentType("application/json")
                .accept("text/event-stream")
                .content("{\"message\":\"Explain a moving average\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(pendingResponse))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:text_delta")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Reply from test provider.")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:message_completed")));

        mockMvc.perform(get("/api/ai/conversations/{id}/messages", id).with(user("stream-user")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].role").value("user"))
            .andExpect(jsonPath("$[1].role").value("assistant"))
            .andExpect(jsonPath("$[1].content").value("Reply from test provider."));
    }

    @TestConfiguration
    static class FakeProviderConfiguration {

        @Bean
        @Primary
        AiProvider fakeAiProvider() {
            return (messages, onText, isCancelled) -> {
                if (!isCancelled.getAsBoolean()) {
                    onText.accept("Reply from test provider.");
                }
            };
        }
    }
}
