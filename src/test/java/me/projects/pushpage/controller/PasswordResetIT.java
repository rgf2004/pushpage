package me.projects.pushpage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
import me.projects.pushpage.util.ApiKeyHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Password reset with EMAIL_ENABLED off: the admin hands over the link. */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetIT extends PostgresTestSupport {

    static final String ADMIN_KEY = "pp_pwreset-admin-key";
    static final String USER_EMAIL = "carol@example.com";
    static final String OLD_PASSWORD = "old-password-1";
    static final String NEW_PASSWORD = "new-password-2";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pages-dir", tempDir::toString);
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiKeyAuthFilter apiKeyAuthFilter;
    final ObjectMapper json = new ObjectMapper();

    MockMvc mockMvc;
    String userId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter).build();
        jdbc.execute("DELETE FROM password_reset_tokens");
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        jdbc.update("INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, true)",
                "pwadmin1", ApiKeyHasher.hash(ADMIN_KEY), Timestamp.from(Instant.now()));
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(body(USER_EMAIL, OLD_PASSWORD))).andExpect(status().isCreated());
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, USER_EMAIL);
    }

    private String body(String email, String password) throws Exception {
        return json.writeValueAsString(java.util.Map.of("email", email, "password", password));
    }

    private int loginStatus(String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(USER_EMAIL, password))).andReturn().getResponse().getStatus();
    }

    private String loginJwt(String password) throws Exception {
        String response = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(USER_EMAIL, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("jwt").asText();
    }

    private int pagesStatusWithJwt(String jwt) throws Exception {
        return mockMvc.perform(get("/pages").header("Authorization", "Bearer " + jwt))
                .andReturn().getResponse().getStatus();
    }

    private String adminIssuesToken() throws Exception {
        String response = mockMvc.perform(post("/admin/users/" + userId + "/reset-password")
                        .header("X-Api-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset_link").value(org.hamcrest.Matchers.containsString("/dashboard?reset_token=")))
                .andExpect(jsonPath("$.expires_at").exists())
                .andReturn().getResponse().getContentAsString();
        String link = json.readTree(response).get("reset_link").asText();
        return link.substring(link.indexOf("reset_token=") + "reset_token=".length());
    }

    private int reset(String token, String password) throws Exception {
        return mockMvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("token", token, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void adminIssuedLink_resetsPassword_andOldPasswordStopsWorking() throws Exception {
        String token = adminIssuesToken();

        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(204);

        assertThat(loginStatus(NEW_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(OLD_PASSWORD)).isEqualTo(401);
    }

    @Test
    void token_isSingleUse() throws Exception {
        String token = adminIssuesToken();

        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(204);
        assertThat(reset(token, "another-password-3")).isEqualTo(400);

        assertThat(loginStatus(NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void newLinkReplacesTheOldOne() throws Exception {
        String first = adminIssuesToken();
        String second = adminIssuesToken();

        assertThat(reset(first, NEW_PASSWORD)).isEqualTo(400);
        assertThat(reset(second, NEW_PASSWORD)).isEqualTo(204);
    }

    @Test
    void expiredToken_isRejected() throws Exception {
        String token = adminIssuesToken();
        jdbc.update("UPDATE password_reset_tokens SET expires_at = ?", Timestamp.from(Instant.now().minusSeconds(5)));

        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(400);
        assertThat(loginStatus(OLD_PASSWORD)).isEqualTo(200);
    }

    @Test
    void tooShortPassword_isRejected_andTokenSurvivesForARetry() throws Exception {
        String token = adminIssuesToken();

        assertThat(reset(token, "short")).isEqualTo(400);
        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(204);
    }

    @Test
    void garbageToken_isRejected() throws Exception {
        assertThat(reset("not-a-real-token", NEW_PASSWORD)).isEqualTo(400);
    }

    @Test
    void tokenIsStoredHashedNeverRaw() throws Exception {
        String token = adminIssuesToken();

        String stored = jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens", String.class);
        assertThat(stored).isNotEqualTo(token).hasSize(64);
    }

    @Test
    void resettingThePassword_invalidatesExistingJwts_butNewLoginsWork() throws Exception {
        String oldJwt = loginJwt(OLD_PASSWORD);
        assertThat(pagesStatusWithJwt(oldJwt)).isEqualTo(200);

        assertThat(reset(adminIssuesToken(), NEW_PASSWORD)).isEqualTo(204);

        assertThat(pagesStatusWithJwt(oldJwt)).isEqualTo(401);
        assertThat(pagesStatusWithJwt(loginJwt(NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    void resettingThePassword_doesNotInvalidateApiKeys() throws Exception {
        assertThat(reset(adminIssuesToken(), NEW_PASSWORD)).isEqualTo(204);

        mockMvc.perform(get("/pages").header("X-Api-Key", ADMIN_KEY)).andExpect(status().isOk());
    }

    @Test
    void deactivatedUser_cannotRedeemAnOutstandingToken() throws Exception {
        String token = adminIssuesToken();
        jdbc.update("UPDATE users SET active = false WHERE id = ?", userId);

        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(400);
    }

    @Test
    void adminEndpoint_requiresAdmin() throws Exception {
        String userJwt = loginJwt(OLD_PASSWORD);

        mockMvc.perform(post("/admin/users/" + userId + "/reset-password")
                        .header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/users/" + userId + "/reset-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoint_unknownAndDeactivatedUsers() throws Exception {
        mockMvc.perform(post("/admin/users/nobody/reset-password").header("X-Api-Key", ADMIN_KEY))
                .andExpect(status().isNotFound());

        jdbc.update("UPDATE users SET active = false WHERE id = ?", userId);
        mockMvc.perform(post("/admin/users/" + userId + "/reset-password").header("X-Api-Key", ADMIN_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPasswordEndpoint_doesNotExistWithoutEmail() throws Exception {
        mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("email", USER_EMAIL))))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Long.class)).isZero();
    }
}
