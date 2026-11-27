package com.currencyanalyzer.web;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.ai.rag.RagDocumentService;
import com.currencyanalyzer.ai.rag.RagStartupSyncRunner;
import com.currencyanalyzer.ai.rag.RagSyncSummary;
import com.currencyanalyzer.auth.CompatiblePbkdf2PasswordEncoder;
import com.currencyanalyzer.auth.KnowledgeAdminService;
import com.currencyanalyzer.auth.PersistentSessionService;
import com.currencyanalyzer.auth.UserRepository;
import com.currencyanalyzer.auth.UserRole;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:persistent-session;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.auth.registration-enabled=true",
        "app.rag.admin-users=ps_admin"
    }
)
@AutoConfigureMockMvc
class PersistentSessionIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompatiblePbkdf2PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private KnowledgeAdminService knowledgeAdminService;

    @BeforeEach
    void ensureAdminExists() {
        userRepository.findByUsername("ps_admin")
            .orElseGet(() -> userRepository.create(
                "ps_admin",
                passwordEncoder.encode("admin-pass-123"),
                UserRole.ADMIN,
                "PS Admin"
            ));
    }

    @Test
    void sessionCookieSurvivesMissingHttpSessionRotatesOnPasswordChangesAndEnforcesDisableAndLogout() throws Exception {
        register("ps_trader", "trader-pass-123");
        long traderId = userRepository.findByUsername("ps_trader").orElseThrow().id();

        MvcResult adminLogin = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"ps_admin","password":"admin-pass-123"}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        MockHttpSession adminSession = (MockHttpSession) adminLogin.getRequest().getSession(false);

        // 1. Login issues HttpOnly CA_SESSION cookie; requests with ONLY that cookie (no HttpSession) succeed
        MvcResult traderLogin = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"ps_trader","password":"trader-pass-123"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(header().string("Set-Cookie", containsString(PersistentSessionService.COOKIE_NAME + "=")))
            .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
            .andReturn();

        Cookie caSession = traderLogin.getResponse().getCookie(PersistentSessionService.COOKIE_NAME);
        assertNotNull(caSession);
        assertTrue(caSession.isHttpOnly());
        assertFalse(caSession.getValue().isBlank());

        // Simulate Cloud Run container recycle: send ONLY caSession cookie with NO HttpSession
        mockMvc.perform(get("/api/auth/status").cookie(caSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logged_in").value(true))
            .andExpect(jsonPath("$.username").value("ps_trader"))
            .andExpect(jsonPath("$.role").value("MEMBER"));

        mockMvc.perform(get("/api/settings").cookie(caSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.trend_threshold").isNumber());

        // 2. Self password change rotates CA_SESSION; new cookie works while old cookie is rejected & cleared
        MvcResult pwChange = mockMvc.perform(post("/api/auth/change-password")
                .cookie(caSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"current_password":"trader-pass-123","new_password":"trader-pass-456"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();

        Cookie rotatedCookie = pwChange.getResponse().getCookie(PersistentSessionService.COOKIE_NAME);
        assertNotNull(rotatedCookie);
        assertNotEquals(caSession.getValue(), rotatedCookie.getValue());

        mockMvc.perform(get("/api/auth/status").cookie(rotatedCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logged_in").value(true))
            .andExpect(jsonPath("$.username").value("ps_trader"));

        MvcResult oldRejected = mockMvc.perform(get("/api/settings").cookie(caSession))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
            .andReturn();
        assertEquals("", oldRejected.getResponse().getCookie(PersistentSessionService.COOKIE_NAME).getValue());

        // 3. Admin password reset invalidates existing rotatedCookie
        mockMvc.perform(post("/api/admin/users/{id}/reset-password", traderId)
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"password":"trader-pass-789"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/settings").cookie(rotatedCookie))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        // Re-login with reset password to get a fresh CA_SESSION cookie
        MvcResult relogin = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"ps_trader","password":"trader-pass-789"}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        Cookie freshCookie = relogin.getResponse().getCookie(PersistentSessionService.COOKIE_NAME);
        assertNotNull(freshCookie);

        // 4. Logout clears CA_SESSION (Max-Age=0) and rejects subsequent requests
        mockMvc.perform(post("/api/auth/logout").cookie(freshCookie).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        // Tampered & malformed cookies are rejected and cleared safely
        mockMvc.perform(get("/api/settings").cookie(new Cookie(PersistentSessionService.COOKIE_NAME, "v1.not-valid!@#")))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        mockMvc.perform(get("/api/settings").cookie(new Cookie(PersistentSessionService.COOKIE_NAME, "v1.dXNlcg.1000.abcd")))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        // Re-login again, then admin disables ps_trader -> cookie-only request returns 403 and clears cookie
        MvcResult reloginBeforeDisable = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"ps_trader","password":"trader-pass-789"}
                    """))
            .andExpect(status().isOk())
            .andReturn();
        Cookie beforeDisableCookie = reloginBeforeDisable.getResponse().getCookie(PersistentSessionService.COOKIE_NAME);

        mockMvc.perform(put("/api/admin/users/{id}", traderId)
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"MEMBER","status":"DISABLED"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/status").cookie(beforeDisableCookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("This account has been disabled"))
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        assertEquals(UserRole.MEMBER, userRepository.findById(traderId).orElseThrow().role());

        // Also cover direct secret override, unknown user, invalid hex, and RagStartupSyncRunner delay
        PersistentSessionService secretBacked = new PersistentSessionService(
            jdbcClient,
            userRepository,
            knowledgeAdminService,
            Clock.fixed(Instant.parse("2026-11-27T10:00:00Z"), ZoneOffset.UTC),
            "unit-test-configured-secret",
            true,
            Duration.ofHours(12)
        );
        MockHttpServletResponse issuedRes = new MockHttpServletResponse();
        secretBacked.issueSessionCookie(issuedRes, "ps_admin");
        Cookie adminCookie = issuedRes.getCookie(PersistentSessionService.COOKIE_NAME);
        assertNotNull(adminCookie);
        assertTrue(adminCookie.getSecure());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(adminCookie);
        MockHttpServletResponse valRes = new MockHttpServletResponse();
        assertTrue(secretBacked.validateRequestCookie(req, valRes).isPresent());
        assertEquals(UserRole.ADMIN, secretBacked.resolveEffectiveRole(userRepository.findByUsername("ps_admin").orElseThrow()));

        String futureTs = Long.toString(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond());
        String ghostUser = Base64.getUrlEncoder().withoutPadding().encodeToString("ghost_user".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest ghostReq = new MockHttpServletRequest();
        ghostReq.setCookies(new Cookie(PersistentSessionService.COOKIE_NAME, "v1." + ghostUser + "." + futureTs + ".00ff"));
        assertEquals(Optional.empty(), secretBacked.validateRequestCookie(ghostReq, new MockHttpServletResponse()));

        MockHttpServletRequest badHexReq = new MockHttpServletRequest();
        badHexReq.setCookies(new Cookie(PersistentSessionService.COOKIE_NAME, "v1." + ghostUser + "." + futureTs + ".zzzz"));
        assertEquals(Optional.empty(), secretBacked.validateRequestCookie(badHexReq, new MockHttpServletResponse()));

        RagDocumentService mockDocService = Mockito.mock(RagDocumentService.class);
        Mockito.when(mockDocService.syncFromStorage(null, "system"))
            .thenReturn(new RagSyncSummary("gcs", "bkt", "knowledge/", 0, 0, 0, 0, 0));
        RagStartupSyncRunner delayedRunner = new RagStartupSyncRunner(mockDocService, 10L);
        delayedRunner.run(new DefaultApplicationArguments());
        Mockito.verify(mockDocService, Mockito.timeout(2000)).syncFromStorage(null, "system");
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"%s","password":"%s"}
                    """.formatted(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
