package me.projects.pushpage.controller;

import me.projects.pushpage.TestUsers;

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
        TestUsers.insert(jdbc, TEST_USER_ID, null, true, TEST_API_KEY);
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
    void health_isPublicAndReturnsOnlyTheStatus() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.uptimeSeconds").doesNotExist())
                .andExpect(jsonPath("$.livePages").doesNotExist())
                .andExpect(jsonPath("$.deletedPages").doesNotExist())
                .andExpect(jsonPath("$.oldestPage").doesNotExist())
                .andExpect(jsonPath("$.newestPage").doesNotExist())
                .andExpect(jsonPath("$.storage").doesNotExist());
    }

    @Test
    void adminHealth_withAdminKey_returnsTheFullPayload() throws Exception {
        mockMvc.perform(get("/admin/health").header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
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
    void adminHealth_withPages_returnsOldestAndNewestTimestamps() throws Exception {
        mockMvc.perform(post("/pages")
                        .header("X-Api-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"html": "<h1>Health test</h1>", "title": "Health Test"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/health").header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldestPage").exists())
                .andExpect(jsonPath("$.newestPage").exists());
    }

    @Test
    void adminHealth_withNoPages_omitsPageTimestamps() throws Exception {
        mockMvc.perform(get("/admin/health").header("X-Api-Key", TEST_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oldestPage").doesNotExist())
                .andExpect(jsonPath("$.newestPage").doesNotExist());
    }

    @Test
    void adminHealth_withRegularUserKey_returns403() throws Exception {
        TestUsers.insert(jdbc, "healthreg1", null, false, "pp_health-regular-key");

        mockMvc.perform(get("/admin/health").header("X-Api-Key", "pp_health-regular-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminHealth_withoutCredentials_returns401() throws Exception {
        mockMvc.perform(get("/admin/health")).andExpect(status().isUnauthorized());
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
        TestUsers.insert(jdbc, "regularguest1", null, false, "pp_regular-guest-key");

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
        TestUsers.insert(jdbc, "otheruser1", null, false, "pp_other-api-key");

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
        TestUsers.insert(jdbc, "otheruser2", null, false, "pp_other-api-key-2");

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
        TestUsers.insert(jdbc, "regularuser1", null, false, "pp_regular-api-key");

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

        // create an API key using the JWT
        String tokenResponse = mockMvc.perform(post("/me/keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"alice laptop\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").exists())
                .andReturn().getResponse().getContentAsString();

        String apiKey = tokenResponse.replaceAll(".*\"api_key\":\"([^\"]+)\".*", "$1");

        // publish with the new API key
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
        TestUsers.insert(jdbc, "legacyu1", "legacy@example.com", false, "pp_legacy-key");

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

    private String signUpAndLogin(String email) throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"password123\"}"))
                .andExpect(status().isCreated());
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"jwt\":\"([^\"]+)\".*", "$1");
    }

    private String createKey(String jwt, String name) throws Exception {
        return mockMvc.perform(post("/me/keys")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String field(String json, String name) {
        return json.replaceAll(".*\"" + name + "\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void newAccount_hasNoApiKeysUntilOneIsCreated() throws Exception {
        String jwt = signUpAndLogin("dana@example.com");

        mockMvc.perform(get("/me/keys").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void createKey_returnsRawKeyOnce_andItAuthenticates() throws Exception {
        String jwt = signUpAndLogin("erin@example.com");

        String created = createKey(jwt, "Claude Code on laptop");
        String rawKey = field(created, "api_key");

        assertThat(rawKey).startsWith("pp_");
        assertThat(field(created, "name")).isEqualTo("Claude Code on laptop");
        assertThat(field(created, "key_prefix")).isEqualTo(rawKey.substring(0, 8));
        mockMvc.perform(get("/pages").header("X-Api-Key", rawKey)).andExpect(status().isOk());

        // listing never contains the raw key
        String listing = mockMvc.perform(get("/me/keys").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Claude Code on laptop"))
                .andExpect(jsonPath("$[0].key_prefix").value(rawKey.substring(0, 8)))
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(rawKey);
    }

    @Test
    void creatingASecondKey_doesNotInvalidateTheFirst() throws Exception {
        String jwt = signUpAndLogin("frank@example.com");
        String firstKey = field(createKey(jwt, "laptop"), "api_key");
        String secondKey = field(createKey(jwt, "ci"), "api_key");

        mockMvc.perform(get("/pages").header("X-Api-Key", firstKey)).andExpect(status().isOk());
        mockMvc.perform(get("/pages").header("X-Api-Key", secondKey)).andExpect(status().isOk());
    }

    @Test
    void revokeKey_invalidatesOnlyThatKey() throws Exception {
        String jwt = signUpAndLogin("gina@example.com");
        String first = createKey(jwt, "laptop");
        String second = createKey(jwt, "ci");

        mockMvc.perform(delete("/me/keys/" + field(first, "id")).header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/pages").header("X-Api-Key", field(first, "api_key")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/pages").header("X-Api-Key", field(second, "api_key")))
                .andExpect(status().isOk());
    }

    @Test
    void revokeKey_ofAnotherUser_returns404() throws Exception {
        String jwtA = signUpAndLogin("hank@example.com");
        String keyA = createKey(jwtA, "hank key");
        String jwtB = signUpAndLogin("ivy@example.com");

        mockMvc.perform(delete("/me/keys/" + field(keyA, "id")).header("Authorization", "Bearer " + jwtB))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/pages").header("X-Api-Key", field(keyA, "api_key")))
                .andExpect(status().isOk());
    }

    @Test
    void keyManagement_withAnApiKey_isForbidden() throws Exception {
        String jwt = signUpAndLogin("jack@example.com");
        String rawKey = field(createKey(jwt, "laptop"), "api_key");

        // a leaked key must not be able to mint or list keys; that needs a signed-in session
        mockMvc.perform(post("/me/keys").header("X-Api-Key", rawKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"sneaky\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/me/keys").header("X-Api-Key", rawKey)).andExpect(status().isForbidden());
    }

    @Test
    void createKey_withBlankOrTooLongName_returns400() throws Exception {
        String jwt = signUpAndLogin("kate@example.com");

        mockMvc.perform(post("/me/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/me/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + "x".repeat(61) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createKey_beyondTheLimit_returns409() throws Exception {
        String jwt = signUpAndLogin("liam@example.com");
        for (int i = 0; i < 10; i++) {
            createKey(jwt, "key " + i);
        }

        mockMvc.perform(post("/me/keys").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"one too many\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void oldRotateEndpoint_isGone() throws Exception {
        String jwt = signUpAndLogin("mona@example.com");

        mockMvc.perform(post("/me/tokens").header("Authorization", "Bearer " + jwt))
                .andExpect(status().is4xxClientError());
    }
}
