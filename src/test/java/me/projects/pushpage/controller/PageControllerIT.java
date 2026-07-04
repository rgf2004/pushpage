package me.projects.pushpage.controller;

import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.filter.ApiKeyAuthFilter;
import me.projects.pushpage.filter.RateLimitFilter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest
@ActiveProfiles("test")
class PageControllerIT extends PostgresTestSupport {

    static final String TEST_API_KEY = "pp_test-admin-api-key";
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

    @Autowired
    RateLimitFilter rateLimitFilter;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter, rateLimitFilter).build();
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, true)",
                TEST_USER_ID, ApiKeyHasher.hash(TEST_API_KEY), Timestamp.from(Instant.now())
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
    void publishPage_returnsRateLimitHeaders() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-RateLimit-Limit"))
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().exists("X-RateLimit-Reset"));
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
    void deletePage_rowRemainsInDatabaseWithDeletedAtSet() throws Exception {
        String response = mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Soft delete me</h1>", "title": "Soft Delete Test"}
                                """))
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(delete("/pages/" + id)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isNoContent());

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pages WHERE id = ?", Integer.class, id);
        assertThat(rowCount).isEqualTo(1);

        String deletedAt = jdbc.queryForObject(
                "SELECT deleted_at FROM pages WHERE id = ?", String.class, id);
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void deletePage_pageNoLongerAppearsInList() throws Exception {
        String response = mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>To be deleted</h1>", "title": "Deleted Page"}
                                """))
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(delete("/pages/" + id)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/pages")
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").doesNotExist());
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
    void publish_withoutApiKey_asGuest_returns200() throws Exception {
        mockMvc.perform(post("/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Guest Page"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void publish_asGuest_pageHasNullUserIdAndShortExpiry() throws Exception {
        String response = mockMvc.perform(post("/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Guest</h1>", "title": "Guest"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        String userId = jdbc.queryForObject(
                "SELECT user_id FROM pages WHERE id = ?", String.class, id);
        assertThat(userId).isNull();

        String expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM pages WHERE id = ?", String.class, id);
        assertThat(expiresAt).isNotNull();
    }

    @Test
    void publish_asGuest_pageVisibleToAdminButNotRegularUser() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, false)",
                "regularguest1", ApiKeyHasher.hash("pp_regular-guest-key"), Timestamp.from(Instant.now())
        );

        mockMvc.perform(post("/pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Guest</h1>", "title": "Guest"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/pages").header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/pages").header("X-Api-Key", "pp_regular-guest-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void publish_withInvalidApiKey_returns401() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", "pp_bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publish_withXApiKeyHeader_returns200() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Hello</h1>", "title": "Test"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void listPages_scopedToCurrentUser() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, false)",
                "otheruser1", ApiKeyHasher.hash("pp_other-api-key"), Timestamp.from(Instant.now())
        );

        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Admin page</h1>", "title": "Admin Page"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", "pp_other-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Other page</h1>", "title": "Other Page"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/pages").header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/pages").header("X-Api-Key", "pp_other-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void deletePage_byNonOwner_returns403() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, false)",
                "otheruser2", ApiKeyHasher.hash("pp_other-api-key-2"), Timestamp.from(Instant.now())
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
                        .header("X-Api-Key", "pp_other-api-key-2"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpoints_withNonAdminKey_return403() throws Exception {
        jdbc.update(
                "INSERT INTO users (id, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, true, false)",
                "regularuser1", ApiKeyHasher.hash("pp_regular-api-key"), Timestamp.from(Instant.now())
        );

        mockMvc.perform(get("/admin/users")
                        .header("X-Api-Key", "pp_regular-api-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signup_thenLoginForJwt_thenRotateApiKey_thenPublish() throws Exception {
        // sign up
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "password": "securepass1"}
                                """))
                .andExpect(status().isCreated());

        // login → get JWT
        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "alice@example.com", "password": "securepass1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").exists())
                .andReturn().getResponse().getContentAsString();

        String jwt = loginResponse.replaceAll(".*\"jwt\":\"([^\"]+)\".*", "$1");

        // rotate API key using JWT
        String tokenResponse = mockMvc.perform(post("/me/tokens")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.api_key").exists())
                .andReturn().getResponse().getContentAsString();

        String apiKey = tokenResponse.replaceAll(".*\"api_key\":\"([^\"]+)\".*", "$1");

        // publish with the rotated API key
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Alice's page</h1>", "title": "Alice"}
                                """))
                .andExpect(status().isOk());
    }

    // ── Multipart upload ────────────────────────────────────────────────────────

    @Test
    void publishFile_withValidHtmlFile_returns200WithUrlAndId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.html", "text/html",
                "<html><head><title>My Report</title></head><body><h1>Hi</h1></body></html>".getBytes());

        mockMvc.perform(multipart("/pages").file(file)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void publishFile_titleExtractedFromHtmlTag() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.html", "text/html",
                "<!DOCTYPE html><html><head><title>Tag Title</title></head><body></body></html>".getBytes());

        String response = mockMvc.perform(multipart("/pages").file(file)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String title = jdbc.queryForObject("SELECT title FROM pages WHERE id = ?", String.class, id);
        assertThat(title).isEqualTo("Tag Title");
    }

    @Test
    void publishFile_titleFallsBackToFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "my-page.html", "text/html",
                "<html><body><h1>No title tag</h1></body></html>".getBytes());

        String response = mockMvc.perform(multipart("/pages").file(file)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String title = jdbc.queryForObject("SELECT title FROM pages WHERE id = ?", String.class, id);
        assertThat(title).isEqualTo("my-page");
    }

    @Test
    void publishFile_titleFallsBackToUntitled_whenNoTagAndNoFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "", "text/html",
                "<html><body><h1>No title</h1></body></html>".getBytes());

        String response = mockMvc.perform(multipart("/pages").file(file)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = response.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String title = jdbc.queryForObject("SELECT title FROM pages WHERE id = ?", String.class, id);
        assertThat(title).isEqualTo("Untitled");
    }

    @Test
    void publishFile_oversizedFile_returns413() throws Exception {
        byte[] oversized = new byte[2 * 1024 * 1024]; // 2 MB
        MockMultipartFile file = new MockMultipartFile("file", "big.html", "text/html", oversized);

        mockMvc.perform(multipart("/pages").file(file)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void publishFile_missingFilePart_returns400() throws Exception {
        mockMvc.perform(multipart("/pages")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishFile_asGuest_returns200() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guest.html", "text/html",
                "<html><body><h1>Guest</h1></body></html>".getBytes());

        mockMvc.perform(multipart("/pages").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void publishFile_jsonVariantStillWorks() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Still works</h1>", "title": "JSON"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void publishFile_returnsXMaxFileSizeHeader() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "check.html", "text/html",
                "<html><body>hi</body></html>".getBytes());

        mockMvc.perform(multipart("/pages").file(file)
                        .header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Max-File-Size"));
    }

    @Test
    void signup_duplicateEmail_returns409() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "dup@example.com", "password": "securepass1"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "dup@example.com", "password": "anotherpass"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void signup_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "weak@example.com", "password": "short"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "bob@example.com", "password": "correctpassword"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "bob@example.com", "password": "wrongpassword"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_preExistingUserWithNoPassword_returns401() throws Exception {
        // user inserted without password_hash (simulates pre-migration admin-created user)
        jdbc.update(
                "INSERT INTO users (id, email, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, true, false)",
                "legacyu1", "legacy@example.com", ApiKeyHasher.hash("pp_legacy-key"), Timestamp.from(Instant.now())
        );

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "legacy@example.com", "password": "anypassword"}
                                """))
                .andExpect(status().isUnauthorized());

        // but they can still authenticate with their API key
        mockMvc.perform(get("/pages")
                        .header("X-Api-Key", "pp_legacy-key"))
                .andExpect(status().isOk());
    }

    @Test
    void rotateApiToken_withJwt_thenOldKeyIsInvalid() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "charlie@example.com", "password": "password123"}
                                """))
                .andExpect(status().isCreated());

        String jwt = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "charlie@example.com", "password": "password123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"jwt\":\"([^\"]+)\".*", "$1");

        // get first key
        String firstKey = mockMvc.perform(post("/me/tokens")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"api_key\":\"([^\"]+)\".*", "$1");

        // rotate again — get second key
        String secondKey = mockMvc.perform(post("/me/tokens")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"api_key\":\"([^\"]+)\".*", "$1");

        // first key is now invalid
        mockMvc.perform(get("/pages").header("X-Api-Key", firstKey))
                .andExpect(status().isUnauthorized());

        // second key works
        mockMvc.perform(get("/pages").header("X-Api-Key", secondKey))
                .andExpect(status().isOk());
    }
}
