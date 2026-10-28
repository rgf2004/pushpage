package me.projects.pushpage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.email.sender.EmailService;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self-service forgot-password with EMAIL_ENABLED on (SMTP replaced by a mock). */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.features.email.enabled=true", "app.email.from=test@example.com",
        "spring.mail.host=localhost"})
class PasswordResetEmailIT extends PostgresTestSupport {

    static final String EMAIL = "dave@example.com";
    static final String OLD_PASSWORD = "old-password-1";
    static final String NEW_PASSWORD = "new-password-2";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pages-dir", tempDir::toString);
    }

    @MockitoBean EmailService emailService;

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiKeyAuthFilter apiKeyAuthFilter;
    final ObjectMapper json = new ObjectMapper();
    @Autowired me.projects.pushpage.service.RateLimitService rateLimitService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter).build();
        rateLimitService.clearBuckets();
        org.mockito.Mockito.reset(emailService);
        jdbc.execute("DELETE FROM password_reset_tokens");
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(body(EMAIL, OLD_PASSWORD))).andExpect(status().isCreated());
    }

    private String body(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    private int forgot(String email) throws Exception {
        return mockMvc.perform(post("/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email)))).andReturn().getResponse().getStatus();
    }

    private String emailedToken() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(emailService, timeout(2000)).sendPasswordResetEmail(eq(EMAIL), link.capture(), anyLong());
        return link.getValue().substring(link.getValue().indexOf("reset_token=") + "reset_token=".length());
    }

    private int login(String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(EMAIL, password))).andReturn().getResponse().getStatus();
    }

    @Test
    void forgotPassword_emailsLink_whichResetsPassword_andProvesTheMailbox() throws Exception {
        // sign-up verification is on, so the unverified account cannot log in yet
        assertThat(login(OLD_PASSWORD)).isEqualTo(403);

        assertThat(forgot(EMAIL)).isEqualTo(202);
        String token = emailedToken();

        int resetStatus = mockMvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "password", NEW_PASSWORD))))
                .andReturn().getResponse().getStatus();
        assertThat(resetStatus).isEqualTo(204);

        // receiving the email proved mailbox ownership, so login now works with the new password
        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
        assertThat(login(OLD_PASSWORD)).isEqualTo(401);
    }

    @Test
    void unknownEmail_getsTheSameAnswer_andNoEmailIsSent() throws Exception {
        assertThat(forgot("nobody@example.com")).isEqualTo(202);

        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString(), anyLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens", Long.class)).isZero();
    }

    @Test
    void tooManyRequestsFromOneIp_return429() throws Exception {
        int last = 0;
        for (int i = 0; i < 6; i++) {
            last = forgot("nobody" + i + "@example.com");
        }
        assertThat(last).isEqualTo(429);
    }

    @Test
    void adminIssuedLink_doesNotMarkTheEmailVerified() throws Exception {
        jdbc.update("INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES ('adm00001', 'h', now(), true, true)");
        String userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, EMAIL);
        // an admin-delivered token proves nothing about the mailbox
        var issued = wac.getBean(me.projects.pushpage.service.PasswordResetService.class)
                .issueTokenForAdmin(userId);

        mockMvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", issued.rawToken(), "password", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        assertThat(login(NEW_PASSWORD)).isEqualTo(403);
    }
}
