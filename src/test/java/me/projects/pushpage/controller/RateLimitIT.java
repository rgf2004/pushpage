package me.projects.pushpage.controller;

import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
import me.projects.pushpage.filter.RateLimitFilter;
import me.projects.pushpage.service.RateLimitService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.rate-limit.user-requests-per-minute=2",
        "app.rate-limit.guest-requests-per-minute=1"
})
class RateLimitIT extends PostgresTestSupport {

    static final String TEST_API_KEY = "pp_rl-test-api-key";
    static final String TEST_USER_ID = "rl-test-user1";
    static final String ADMIN_API_KEY = "pp_rl-admin-api-key";
    static final String ADMIN_USER_ID = "rl-admin-user1";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pages-dir", tempDir::toString);
    }

    @Autowired
    WebApplicationContext wac;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApiKeyAuthFilter apiKeyAuthFilter;

    @Autowired
    RateLimitFilter rateLimitFilter;

    @Autowired
    RateLimitService rateLimitService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter, rateLimitFilter).build();
        rateLimitService.clearBuckets();
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, false)",
                TEST_USER_ID, ApiKeyHasher.hash(TEST_API_KEY), Timestamp.from(Instant.now())
        );
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, true)",
                ADMIN_USER_ID, ApiKeyHasher.hash(ADMIN_API_KEY), Timestamp.from(Instant.now())
        );
    }

    @Test
    void publish_withinLimit_returns200WithRateLimitHeaders() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    void publish_exceedingLimit_returns429WithRetryAfter() throws Exception {
        String body = """
                {"html": "<h1>Hello</h1>", "title": "Test"}
                """;

        // exhaust the 2-request bucket
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        // 3rd request should be rate-limited
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    @Test
    void publish_rateLimitScopedPerUser_differentUsersBucketedSeparately() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, false)",
                "rl-other-user", ApiKeyHasher.hash("pp_rl-other-key"), Timestamp.from(Instant.now())
        );

        String body = """
                {"html": "<h1>Hello</h1>", "title": "Test"}
                """;

        // exhaust TEST_USER bucket
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());

        // other user is still unaffected
        mockMvc.perform(post("/pages").header("X-Api-Key", "pp_rl-other-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void publish_adminUser_isExemptFromRateLimit() throws Exception {
        String body = """
                {"html": "<h1>Hello</h1>", "title": "Test"}
                """;

        // fire many more than the limit
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/pages").header("X-Api-Key", ADMIN_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void publish_guestRequests_rateLimitedByIp() throws Exception {
        String body = """
                {"html": "<h1>Guest</h1>", "title": "Guest"}
                """;

        // guest limit is 1 RPM — first request passes, second is blocked
        mockMvc.perform(post("/pages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "1"));
        mockMvc.perform(post("/pages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void publish_userAllowedMoreRequestsThanGuest() throws Exception {
        String body = """
                {"html": "<h1>Hello</h1>", "title": "Test"}
                """;

        // guest exhausts on 2nd request (limit=1)
        mockMvc.perform(post("/pages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/pages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        // authenticated user still has capacity (limit=2)
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/pages").header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }
}
