package com.currencyanalyzer.web;

import com.currencyanalyzer.CurrencyAnalyzerApplication;
import com.currencyanalyzer.auth.CompatiblePbkdf2PasswordEncoder;
import com.currencyanalyzer.auth.UserInviteRepository;
import com.currencyanalyzer.auth.UserRepository;
import com.currencyanalyzer.auth.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = CurrencyAnalyzerApplication.class,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:rbac-invites;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
        "app.auth.registration-enabled=false",
        "app.rag.admin-users=boss",
        "app.ai.enabled=true"
    }
)
@AutoConfigureMockMvc
class RbacAndInviteIntegrationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompatiblePbkdf2PasswordEncoder passwordEncoder;

    @Autowired
    private UserInviteRepository inviteRepository;

    private long bossId;

    @BeforeEach
    void ensureAdminExists() {
        bossId = userRepository.findByUsername("boss")
            .orElseGet(() -> userRepository.create(
                "boss",
                passwordEncoder.encode("boss-pass-123"),
                UserRole.ADMIN,
                "Workspace Owner"
            ))
            .id();
    }

    @Test
    void supportsSingleUseInvitesRoleEnforcementLiveSessionKillSwitchAndSelfPasswordChange() throws Exception {
        MockHttpSession adminSession = login("boss", "boss-pass-123");

        mockMvc.perform(get("/api/auth/status").session(adminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.is_admin").value(true))
            .andExpect(jsonPath("$.can_write").value(true))
            .andExpect(jsonPath("$.can_use_ai").value(true))
            .andExpect(jsonPath("$.invite_registration_enabled").value(true));

        // Open signup without an invite remains closed
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"stranger","password":"password123"}
                    """))
            .andExpect(status().isForbidden());

        // Admin issues a single-use MEMBER invite
        MvcResult inviteResult = mockMvc.perform(post("/api/admin/invites")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"MEMBER","expires_in_hours":24,"note":"FX analyst invite"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.invite.role").value("MEMBER"))
            .andExpect(jsonPath("$.invite.state").value("PENDING"))
            .andReturn();

        JsonNode inviteJson = JSON.readTree(inviteResult.getResponse().getContentAsString());
        String inviteCode = inviteJson.get("invite_code").asText();
        assertFalse(inviteCode.isBlank());

        // Invited user redeems single-use code (even with whitespace/lowercase variation)
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"analyst","password":"analyst-pass-123","display_name":"Alex Analyst","invite_code":"%s"}
                    """.formatted(inviteCode.toLowerCase())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        // Second use of same invite code is rejected
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"analyst2","password":"analyst-pass-123","invite_code":"%s"}
                    """.formatted(inviteCode)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("already been used")));

        // Admin also creates a second invite and revokes it before use
        MvcResult revokedInviteResult = mockMvc.perform(post("/api/admin/invites")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"VIEWER","expires_in_hours":12}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode revokedJson = JSON.readTree(revokedInviteResult.getResponse().getContentAsString());
        String revokedId = revokedJson.get("invite").get("id").asText();
        String revokedCode = revokedJson.get("invite_code").asText();

        mockMvc.perform(delete("/api/admin/invites/{id}", revokedId).session(adminSession).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"latecomer","password":"password123","invite_code":"%s"}
                    """.formatted(revokedCode)))
            .andExpect(status().isBadRequest());

        // Admin directly creates a VIEWER user
        MvcResult viewerCreate = mockMvc.perform(post("/api/admin/users")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"username":"auditor","password":"viewer-pass-123","display_name":"Read Only Auditor","role":"VIEWER"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.role").value("VIEWER"))
            .andReturn();
        long viewerId = JSON.readTree(viewerCreate.getResponse().getContentAsString()).get("id").asLong();

        MockHttpSession viewerSession = login("auditor", "viewer-pass-123");
        mockMvc.perform(get("/api/auth/status").session(viewerSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("VIEWER"))
            .andExpect(jsonPath("$.is_admin").value(false))
            .andExpect(jsonPath("$.can_write").value(false))
            .andExpect(jsonPath("$.can_use_ai").value(false));

        // VIEWER can read market, alert, settings, and RAG endpoints, but is blocked from AI and mutations
        mockMvc.perform(get("/api/settings").session(viewerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/alert-rules").session(viewerSession)).andExpect(status().isOk());
        mockMvc.perform(get("/api/rag/documents").session(viewerSession)).andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/conversations").session(viewerSession).with(csrf()))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/alert-rules").session(viewerSession).with(csrf())
                .contentType("application/json")
                .content("""
                    {"pair":"EUR/USD","condition_type":"cross_above","target_value":1.20}
                    """))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/settings").session(viewerSession).with(csrf())
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").session(viewerSession))
            .andExpect(status().isForbidden());

        // VIEWER can update own password cleanly
        mockMvc.perform(post("/api/auth/change-password")
                .session(viewerSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"current_password":"viewer-pass-123","new_password":"viewer-pass-456"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        MockHttpSession viewerRelogin = login("auditor", "viewer-pass-456");
        assertNotNull(viewerRelogin);

        // MEMBER can use AI conversations and create personal alert rules, but cannot call admin endpoints
        MockHttpSession memberSession = login("analyst", "analyst-pass-123");
        mockMvc.perform(post("/api/ai/conversations").session(memberSession).with(csrf()))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/alert-rules").session(memberSession).with(csrf())
                .contentType("application/json")
                .content("""
                    {"pair":"EUR/USD","condition_type":"cross_above","target_value":1.15}
                    """))
            .andExpect(status().isCreated());
        mockMvc.perform(get("/api/admin/users").session(memberSession))
            .andExpect(status().isForbidden());

        // Anti-lockout: admin cannot demote/disable themselves
        mockMvc.perform(put("/api/admin/users/{id}", bossId)
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"MEMBER","status":"ACTIVE"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("own administrator account")));

        // Live session kill-switch: disabling MEMBER immediately blocks active session and future logins
        long analystId = userRepository.findByUsername("analyst").orElseThrow().id();
        mockMvc.perform(put("/api/admin/users/{id}", analystId)
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"MEMBER","status":"DISABLED"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/auth/status").session(memberSession))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("This account has been disabled"));

        mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"analyst","password":"analyst-pass-123"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("This account has been disabled"));

        // Admin resets VIEWER password and lists users + invites cleanly
        mockMvc.perform(post("/api/admin/users/{id}/reset-password", viewerId)
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"password":"admin-reset-789"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
        login("auditor", "admin-reset-789");

        mockMvc.perform(get("/api/admin/users").session(adminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].username").isNotEmpty());
        mockMvc.perform(get("/api/admin/invites").session(adminSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].state").isNotEmpty());

        assertEquals(UserRole.VIEWER, userRepository.findById(viewerId).orElseThrow().role());

        // Live role promotion in existing session (VIEWER -> MEMBER) takes effect without re-login
        mockMvc.perform(put("/api/admin/users/{id}", viewerId)
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"MEMBER","status":"ACTIVE","display_name":"Promoted Member"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MEMBER"))
            .andExpect(jsonPath("$.display_name").value("Promoted Member"));

        mockMvc.perform(post("/api/ai/conversations").session(viewerRelogin).with(csrf()))
            .andExpect(status().isCreated());
    }

    @Test
    void rejectsInvalidPayloadsNotFoundTargetsExpiredInvitesAndProtectsLastAdmin() throws Exception {
        MockHttpSession adminSession = login("boss", "boss-pass-123");

        // Role parser edge cases
        assertEquals(UserRole.ADMIN, UserRole.parse("admin"));
        assertEquals(UserRole.MEMBER, UserRole.parse("Member"));
        assertEquals(UserRole.VIEWER, UserRole.parse("viewer"));
        assertEquals(UserRole.MEMBER, UserRole.fromDatabase(null));
        assertEquals(UserRole.MEMBER, UserRole.fromDatabase("unknown"));
        assertEquals(UserRole.ADMIN, UserRole.fromDatabase("ADMIN"));
        assertEquals(UserRole.VIEWER, UserRole.fromDatabase("VIEWER"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> UserRole.parse(" ")).getMessage().contains("required"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> UserRole.parse("OWNER")).getMessage().contains("ADMIN, MEMBER, or VIEWER"));

        // Duplicate username, validation & not-found handling on admin APIs
        mockMvc.perform(post("/api/admin/users")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"username":"boss","password":"password123","role":"MEMBER"}
                    """))
            .andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/users")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"username":"ab","password":"password123"}
                    """))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/users")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"username":"shortpw","password":"123"}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/users/999999")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"VIEWER"}
                    """))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/users/999999/reset-password")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"password":"password123"}
                    """))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/admin/invites/{id}", UUID.randomUUID())
                .session(adminSession)
                .with(csrf()))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/invites")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"MEMBER","expires_in_hours":999}
                    """))
            .andExpect(status().isBadRequest());

        // Invalid / malformed / expired invite codes
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"badinv","password":"password123","invite_code":"BAD"}
                    """))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"badinv","password":"password123","invite_code":"CA-ZZZZ-ZZZZ-ZZZZ"}
                    """))
            .andExpect(status().isBadRequest());

        String expiredCode = UserInviteRepository.generateInviteCode();
        Instant now = Instant.now();
        inviteRepository.insert(
            expiredCode.substring(0, 10),
            UserInviteRepository.sha256Hex(UserInviteRepository.normalizeCode(expiredCode)),
            UserRole.MEMBER,
            "Expired invite",
            "boss",
            now.minus(1, ChronoUnit.HOURS),
            now.minus(2, ChronoUnit.HOURS)
        );
        mockMvc.perform(post("/api/auth/register")
                .contentType("application/json")
                .content("""
                    {"username":"expireduser","password":"password123","invite_code":"%s"}
                    """.formatted(expiredCode)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("expired")));

        // Self password change error guards
        mockMvc.perform(post("/api/auth/change-password")
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"current_password":"a","new_password":"b"}
                    """))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/change-password")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"current_password":"wrong-password","new_password":"new-password-123"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Current password is incorrect"));

        // Second admin cannot demote the last active admin if acting as sole remaining admin
        MvcResult secondAdminRes = mockMvc.perform(post("/api/admin/users")
                .session(adminSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"username":"opsadmin","password":"ops-pass-123","role":"ADMIN"}
                    """))
            .andExpect(status().isCreated())
            .andReturn();
        long opsId = JSON.readTree(secondAdminRes.getResponse().getContentAsString()).get("id").asLong();
        MockHttpSession opsSession = login("opsadmin", "ops-pass-123");

        // Disable boss first (allowed while opsadmin is active ADMIN), then opsadmin cannot disable themselves
        mockMvc.perform(put("/api/admin/users/{id}", bossId)
                .session(opsSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"ADMIN","status":"DISABLED"}
                    """))
            .andExpect(status().isOk());
        mockMvc.perform(put("/api/admin/users/{id}", opsId)
                .session(opsSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"ADMIN","status":"DISABLED"}
                    """))
            .andExpect(status().isBadRequest());

        // Restore boss cleanly so subsequent test order stays deterministic
        mockMvc.perform(put("/api/admin/users/{id}", bossId)
                .session(opsSession)
                .with(csrf())
                .contentType("application/json")
                .content("""
                    {"role":"ADMIN","status":"ACTIVE"}
                    """))
            .andExpect(status().isOk());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType("application/json")
                .content("""
                    {"username":"%s","password":"%s"}
                    """.formatted(username, password)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
