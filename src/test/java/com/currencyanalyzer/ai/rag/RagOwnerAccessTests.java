package com.currencyanalyzer.ai.rag;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:rag-owner-access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.rag.admin-users="
    }
)
@AutoConfigureMockMvc
class RagOwnerAccessTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void oldestAccountCanUploadWhenAdminListIsEmptyAndLaterUsersCannot() throws Exception {
        register("alice", "secure-password");
        register("bob", "secure-password");

        MockHttpSession alice = login("alice", "secure-password");
        MockHttpSession bob = login("bob", "secure-password");
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "owner-note.md",
            "text/markdown",
            "# Owner note\n\nThe first account can maintain the knowledge base.\n".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/rag/documents").file(file).session(alice).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.uploadedBy").value("alice"));

        mockMvc.perform(multipart("/api/rag/documents").file(file).session(bob).with(csrf()))
            .andExpect(status().isForbidden());
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content(credentials(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content(credentials(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private static String credentials(String username, String password) {
        return """
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(username, password);
    }
}
