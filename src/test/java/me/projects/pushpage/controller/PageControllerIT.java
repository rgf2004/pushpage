package me.projects.pushpage.controller;

import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
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

import me.projects.pushpage.util.ApiKeyHasher;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class PageControllerIT extends PostgresTestSupport {

    static final String TEST_API_KEY = "test-admin-api-key";
    static final String TEST_USER_ID = "testuser1";

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

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter).build();
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, 1, 1)",
                TEST_USER_ID, "testadmin", ApiKeyHasher.hash(TEST_API_KEY), Timestamp.from(Instant.now())
        );
    }

    @Test
    void publishPage_withValidHtml_returns200WithUrlAndId() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void publishPage_withoutTitle_returns200AndExtractsTitleFromHtml() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<!DOCTYPE html><html><head><title>Auto Title</title></head><body></body></html>"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void publishPage_withEmptyHtml_returns400() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "", "title": "Test"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishPage_withOversizedHtml_returns413() throws Exception {
        String oversized = "a".repeat(2 * 1024 * 1024); // 2 MB
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\": \"" + oversized + "\", \"title\": \"Test\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void publishPage_withValidHtml_returnsXMaxFileSizeHeader() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Max-File-Size"));
    }

    @Test
    void listPages_returns200WithJsonArray() throws Exception {
        mockMvc.perform(get("/pages")
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void deletePage_whenPageExists_returns204() throws Exception {
        String response = mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Delete me</h1>", "title": "Temp"}
                                """))
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(delete("/pages/" + id)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletePage_whenIdNotFound_returns404() throws Exception {
        mockMvc.perform(delete("/pages/nonexistent")
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isNotFound());
    }

    @Test
    void health_returns200WithStatusUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void health_returnsRequiredFields() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber())
                .andExpect(jsonPath("$.livePages").isNumber())
                .andExpect(jsonPath("$.deletedPages").isNumber())
                .andExpect(jsonPath("$.storage.usedBytes").isNumber())
                .andExpect(jsonPath("$.storage.usedHuman").exists())
                .andExpect(jsonPath("$.storage.freeBytes").isNumber())
                .andExpect(jsonPath("$.storage.freeHuman").exists());
    }

    @Test
    void health_withPages_returnsOldestAndNewestTimestamps() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Health test</h1>", "title": "Health Test"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldestPage").exists())
                .andExpect(jsonPath("$.newestPage").exists());
    }

    @Test
    void health_withNoPages_omitsPageTimestamps() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldestPage").doesNotExist())
                .andExpect(jsonPath("$.newestPage").doesNotExist());
    }

    @Test
    void publish_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(post("/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publish_withInvalidApiKey_returns401() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publish_withBearerToken_returns200() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("Authorization", "Bearer " + TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void listPages_scopedToCurrentUser() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, 1, 0)",
                "otheruser1", "otheruser", ApiKeyHasher.hash("other-api-key"), Timestamp.from(Instant.now())
        );

        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Admin page</h1>", "title": "Admin Page"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", "other-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Other page</h1>", "title": "Other Page"}
                                """))
                .andExpect(status().isOk());

        // admin sees all pages
        mockMvc.perform(get("/pages").header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // regular user sees only their own
        mockMvc.perform(get("/pages").header("X-Api-Key", "other-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void deletePage_byNonOwner_returns403() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, 1, 0)",
                "otheruser2", "otheruser2", ApiKeyHasher.hash("other-api-key-2"), Timestamp.from(Instant.now())
        );

        String response = mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Admin's page</h1>", "title": "Admin Page"}
                                """))
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(delete("/pages/" + id)
                        .header("X-Api-Key", "other-api-key-2"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoints_withNonAdminKey_return403() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, 1, 0)",
                "regularuser1", "regularuser", ApiKeyHasher.hash("regular-api-key"), Timestamp.from(Instant.now())
        );

        mockMvc.perform(get("/admin/users")
                        .header("X-Api-Key", "regular-api-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createUser_andAuthenticateWithNewKey() throws Exception {
        String createResponse = mockMvc.perform(post("/admin/users")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "newuser", "admin": false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").exists())
                .andReturn().getResponse().getContentAsString();

        String newApiKey = createResponse.replaceAll(".*\"api_key\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", newApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>New user page</h1>", "title": "New User Page"}
                                """))
                .andExpect(status().isOk());
    }
}
