package me.projects.pushpage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.TestUsers;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
import me.projects.pushpage.filter.RateLimitFilter;
import me.projects.pushpage.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PUT /pages/{id}: replace a page's content, keeping its id and URL. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.rate-limit.user-requests-per-minute=8")
class UpdatePageIT extends PostgresTestSupport {

    static final String OWNER_KEY = "pp_upd-owner-key";
    static final String OTHER_KEY = "pp_upd-other-key";
    static final String ADMIN_KEY = "pp_upd-admin-key";
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("app.pages-dir", tempDir::toString);
    }

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiKeyAuthFilter apiKeyAuthFilter;
    @Autowired RateLimitFilter rateLimitFilter;
    @Autowired RateLimitService rateLimitService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter, rateLimitFilter).build();
        rateLimitService.clearBuckets();
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        TestUsers.insert(jdbc, "upd-owner", null, false, OWNER_KEY);
        TestUsers.insert(jdbc, "upd-other", null, false, OTHER_KEY);
        TestUsers.insert(jdbc, "upd-admin", null, true, ADMIN_KEY);
    }

    String publish(String key, String html) throws Exception {
        var req = post("/pages").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("html", html, "title", "First")));
        if (key != null) req = req.header("X-Api-Key", key);
        String body = mockMvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asText();
    }

    ResultActions update(String id, String key, String html) throws Exception {
        var req = put("/pages/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("html", html, "title", "Second")));
        if (key != null) req = req.header("X-Api-Key", key);
        return mockMvc.perform(req);
    }

    String onDisk(String id) throws Exception {
        return Files.readString(tempDir.resolve(id + ".html"));
    }

    Instant expiresAt(String id) {
        return jdbc.queryForObject("SELECT expires_at FROM pages WHERE id = ?", Timestamp.class, id).toInstant();
    }

    @Test
    void owner_canReplaceContentAndTitle_sameIdAndUrl_expiryMovesForward() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");
        Instant soon = Instant.now().plus(1, ChronoUnit.HOURS);
        jdbc.update("UPDATE pages SET expires_at = ? WHERE id = ?", Timestamp.from(soon), id);

        update(id, OWNER_KEY, "<p>new</p>")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.url").value(endsWith("/" + id + ".html")))
                .andExpect(jsonPath("$.expires_at").exists())
                .andExpect(header().exists("X-Max-File-Size"));

        assertThat(onDisk(id)).contains("<p>new</p>").doesNotContain("<p>old</p>");
        assertThat(jdbc.queryForObject("SELECT title FROM pages WHERE id = ?", String.class, id)).isEqualTo("Second");
        assertThat(expiresAt(id)).isAfter(soon.plus(1, ChronoUnit.DAYS));
        assertThat(tempDir.toFile().list()).noneMatch(n -> n.endsWith(".tmp"));
    }

    @Test
    void owner_canUpdateWithAMultipartFile_andTheTitleComesFromTheHtml() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");
        var file = new MockMultipartFile("file", "report.html", "text/html",
                "<!DOCTYPE html><html><head><title>From file</title></head><body>fresh</body></html>".getBytes());

        mockMvc.perform(multipart(HttpMethod.PUT, "/pages/" + id).file(file).header("X-Api-Key", OWNER_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        assertThat(onDisk(id)).contains("fresh");
        assertThat(jdbc.queryForObject("SELECT title FROM pages WHERE id = ?", String.class, id)).isEqualTo("From file");
    }

    @Test
    void anotherUser_gets403_andTheFileIsUntouched() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");

        update(id, OTHER_KEY, "<p>hijack</p>").andExpect(status().isForbidden());

        assertThat(onDisk(id)).contains("<p>old</p>");
    }

    @Test
    void admin_canUpdateAnyPage_andOwnershipStays() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");

        update(id, ADMIN_KEY, "<p>by admin</p>").andExpect(status().isOk());

        assertThat(onDisk(id)).contains("by admin");
        assertThat(jdbc.queryForObject("SELECT user_id FROM pages WHERE id = ?", String.class, id)).isEqualTo("upd-owner");
    }

    @Test
    void guestPage_cannotBeUpdated_notEvenByAnAdmin() throws Exception {
        String id = publish(null, "<p>guest</p>");

        update(id, OWNER_KEY, "<p>x</p>").andExpect(status().isForbidden());
        update(id, ADMIN_KEY, "<p>x</p>").andExpect(status().isForbidden());

        assertThat(onDisk(id)).contains("guest");
    }

    @Test
    void withoutCredentials_is401() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");

        update(id, null, "<p>x</p>").andExpect(status().isUnauthorized());
    }

    @Test
    void unknownDeletedAndExpiredIds_are404() throws Exception {
        update("nope1234", OWNER_KEY, "<p>x</p>").andExpect(status().isNotFound());

        String deleted = publish(OWNER_KEY, "<p>old</p>");
        mockMvc.perform(delete("/pages/" + deleted).header("X-Api-Key", OWNER_KEY)).andExpect(status().isNoContent());
        update(deleted, OWNER_KEY, "<p>x</p>").andExpect(status().isNotFound());
        assertThat(tempDir.resolve(deleted + ".html")).doesNotExist();

        String expired = publish(OWNER_KEY, "<p>old</p>");
        jdbc.update("UPDATE pages SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)), expired);
        update(expired, OWNER_KEY, "<p>x</p>").andExpect(status().isNotFound());
    }

    @Test
    void blankHtml_is400_andOversizedHtml_is413_withTheOldContentKept() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");

        update(id, OWNER_KEY, "  ").andExpect(status().isBadRequest());
        update(id, OWNER_KEY, "a".repeat(2 * 1024 * 1024)).andExpect(status().isPayloadTooLarge());

        assertThat(onDisk(id)).contains("<p>old</p>");
    }

    @Test
    void updates_shareThePublishRateLimit() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>"); // 1 of 8
        for (int i = 1; i <= 7; i++) { // 8 of 8
            update(id, OWNER_KEY, "<p>" + i + "</p>").andExpect(status().isOk());
        }

        update(id, OWNER_KEY, "<p>8</p>")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertThat(onDisk(id)).contains("<p>7</p>");
    }

    @Test
    void admin_isExemptFromTheRateLimit() throws Exception {
        String id = publish(OWNER_KEY, "<p>old</p>");
        for (int i = 0; i < 12; i++) {
            update(id, ADMIN_KEY, "<p>" + i + "</p>").andExpect(status().isOk());
        }
    }
}
