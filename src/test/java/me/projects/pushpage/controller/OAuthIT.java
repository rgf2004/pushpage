package me.projects.pushpage.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class OAuthIT extends PostgresTestSupport {

    static final String REDIRECT = "http://127.0.0.1:33418/callback";
    static final String VERIFIER = "a-long-random-code-verifier-0123456789-abcdefghijklmnop";
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
    String jwt;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).addFilters(apiKeyAuthFilter, rateLimitFilter).build();
        rateLimitService.clearBuckets();
        jdbc.execute("DELETE FROM oauth_clients");
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        TestUsers.insert(jdbc, "admin-oauth", "admin@oauth.test", true, "pp_oauth-admin-key");
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@oauth.test\",\"password\":\"securepass1\"}")).andExpect(status().isCreated());
        jwt = json(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alice@oauth.test\",\"password\":\"securepass1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("jwt").asText();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static JsonNode json(String body) throws Exception {
        return JSON.readTree(body);
    }

    static String challenge(String verifier) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    }

    String registerClient(String redirect) throws Exception {
        return registerNamedClient("Test client", redirect);
    }

    String registerNamedClient(String name, String redirect) throws Exception {
        return json(mockMvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_name\":\"" + name + "\",\"redirect_uris\":[\"" + redirect + "\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("client_id").asText();
    }

    ResultActions authorize(String clientId, String scope, String bearer) throws Exception {
        String body = "{\"client_id\":\"" + clientId + "\",\"redirect_uri\":\"" + REDIRECT + "\",\"code_challenge\":\""
                + challenge(VERIFIER) + "\",\"code_challenge_method\":\"S256\",\"state\":\"xyz\""
                + (scope == null ? "" : ",\"scope\":\"" + scope + "\"") + "}";
        var req = post("/oauth/authorize").contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) req = req.header("Authorization", "Bearer " + bearer);
        return mockMvc.perform(req);
    }

    String codeFor(String clientId, String scope) throws Exception {
        String redirect = json(authorize(clientId, scope, jwt).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get("redirect_to").asText();
        var params = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("xyz");
        return params.getFirst("code");
    }

    ResultActions token(String... kv) throws Exception {
        var req = post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (int i = 0; i < kv.length; i += 2) req = req.param(kv[i], kv[i + 1]);
        return mockMvc.perform(req);
    }

    JsonNode exchange(String clientId, String code) throws Exception {
        return json(token("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", VERIFIER)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    JsonNode signIn(String clientId, String scope) throws Exception {
        return exchange(clientId, codeFor(clientId, scope));
    }

    // ── discovery and registration ────────────────────────────────────────────

    @Test
    void metadataEndpoints_arePublic() throws Exception {
        mockMvc.perform(get("/oauth/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(jsonPath("$.token_endpoint").value(org.hamcrest.Matchers.endsWith("/api/oauth/token")))
                .andExpect(jsonPath("$.registration_endpoint").exists());
        mockMvc.perform(get("/oauth/protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(org.hamcrest.Matchers.endsWith("/mcp/signin")))
                .andExpect(jsonPath("$.authorization_servers[0]").exists());
    }

    @Test
    void register_acceptsHttpsLoopbackAndCustomSchemes() throws Exception {
        registerClient("https://claude.ai/api/mcp/auth_callback");
        registerClient("http://localhost:3000/cb");
        registerClient("cursor://anysphere.cursor-mcp/oauth/callback");
    }

    @Test
    void register_rejectsUnsafeRedirectUris() throws Exception {
        for (String bad : new String[]{"http://evil.example/cb", "javascript:alert(1)", "https://x.example/cb#frag", "not a uri"}) {
            mockMvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"redirect_uris\":[\"" + bad + "\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_redirect_uri"));
        }
    }

    // ── the full flow ─────────────────────────────────────────────────────────

    @Test
    void fullFlow_accessTokenCanPublishListAndDelete_butNothingElse() throws Exception {
        String clientId = registerClient(REDIRECT);
        JsonNode tokens = signIn(clientId, null);
        String access = tokens.get("access_token").asText();
        assertThat(tokens.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("refresh_token").asText()).isNotBlank();

        String id = json(mockMvc.perform(post("/pages").header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"html\":\"<h1>Hi</h1>\",\"title\":\"OAuth\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());

        // not allowed: admin, key management, minting more tokens
        mockMvc.perform(get("/admin/users").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mockMvc.perform(get("/me/keys").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mockMvc.perform(post("/me/keys").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        authorize(clientId, null, access).andExpect(status().isForbidden());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/pages/" + id)
                .header("Authorization", "Bearer " + access)).andExpect(status().isNoContent());
    }

    @Test
    void scope_isEnforced() throws Exception {
        String clientId = registerClient(REDIRECT);
        String access = signIn(clientId, "pages:read").get("access_token").asText();

        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        mockMvc.perform(post("/pages").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"html\":\"<p>x</p>\"}")).andExpect(status().isForbidden());
    }

    // ── authorization endpoint ────────────────────────────────────────────────

    @Test
    void authorize_needsASignedInSession() throws Exception {
        String clientId = registerClient(REDIRECT);
        authorize(clientId, null, null).andExpect(status().isUnauthorized());
        authorize(clientId, null, "not-a-jwt").andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth/authorize").header("X-Api-Key", "pp_oauth-admin-key")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void authorize_rejectsUnregisteredRedirectUriAndMissingPkce() throws Exception {
        String clientId = registerClient(REDIRECT);
        mockMvc.perform(post("/oauth/authorize").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_id\":\"" + clientId + "\",\"redirect_uri\":\"https://evil.example/cb\","
                                + "\"code_challenge\":\"" + challenge(VERIFIER) + "\",\"code_challenge_method\":\"S256\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mockMvc.perform(post("/oauth/authorize").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"client_id\":\"" + clientId + "\",\"redirect_uri\":\"" + REDIRECT + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mockMvc.perform(get("/oauth/client").param("client_id", "nope").param("redirect_uri", REDIRECT))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_client"));
    }

    // ── token endpoint ────────────────────────────────────────────────────────

    @Test
    void code_isSingleUse() throws Exception {
        String clientId = registerClient(REDIRECT);
        String code = codeFor(clientId, null);
        exchange(clientId, code);
        token("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", VERIFIER)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void code_withWrongVerifierOrClient_isRejected() throws Exception {
        String clientId = registerClient(REDIRECT);
        token("grant_type", "authorization_code", "code", codeFor(clientId, null), "redirect_uri", REDIRECT,
                "client_id", clientId, "code_verifier", "wrong-verifier-wrong-verifier-wrong-verifier-123")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));

        String otherClient = registerClient(REDIRECT);
        token("grant_type", "authorization_code", "code", codeFor(clientId, null), "redirect_uri", REDIRECT,
                "client_id", otherClient, "code_verifier", VERIFIER)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void unsupportedGrant_isRejected() throws Exception {
        token("grant_type", "password", "username", "a", "password", "b")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
    }

    @Test
    void refreshToken_rotates_andOldOneStopsWorking() throws Exception {
        String clientId = registerClient(REDIRECT);
        String refresh = signIn(clientId, null).get("refresh_token").asText();

        JsonNode renewed = json(token("grant_type", "refresh_token", "refresh_token", refresh, "client_id", clientId)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(renewed.get("refresh_token").asText()).isNotEqualTo(refresh);
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + renewed.get("access_token").asText()))
                .andExpect(status().isOk());

        token("grant_type", "refresh_token", "refresh_token", refresh, "client_id", clientId)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void refreshToken_isRevokedByAPasswordChange_andForOtherClients() throws Exception {
        String clientId = registerClient(REDIRECT);
        String refresh = signIn(clientId, null).get("refresh_token").asText();
        String other = registerClient(REDIRECT);
        token("grant_type", "refresh_token", "refresh_token", refresh, "client_id", other)
                .andExpect(status().isBadRequest());

        String refresh2 = signIn(clientId, null).get("refresh_token").asText();
        jdbc.update("UPDATE users SET password_changed_at = ? WHERE email = ?",
                Timestamp.from(Instant.now().plusSeconds(5)), "alice@oauth.test");
        token("grant_type", "refresh_token", "refresh_token", refresh2, "client_id", clientId)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void accessToken_stopsWorkingWhenTheAccountIsDeactivated() throws Exception {
        String clientId = registerClient(REDIRECT);
        String access = signIn(clientId, null).get("access_token").asText();
        jdbc.update("UPDATE users SET active = false WHERE email = ?", "alice@oauth.test");
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
    }

    @Test
    void register_isRateLimitedPerIp() throws Exception {
        int limit = rateLimitService.getSignupRequestsPerMinute();
        for (int i = 0; i < limit; i++) registerClient(REDIRECT);
        mockMvc.perform(post("/oauth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[\"" + REDIRECT + "\"]}"))
                .andExpect(status().isTooManyRequests());
    }

    // ── connected apps and revocation ─────────────────────────────────────────

    JsonNode connectedApps(String bearer) throws Exception {
        return json(mockMvc.perform(get("/me/connected-apps").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void connectedApps_listsTheAppWithoutDuplicatesWhenItReconnects() throws Exception {
        String clientId = registerClient(REDIRECT);
        signIn(clientId, "pages:read pages:publish");
        signIn(clientId, null); // the same app authorized again

        JsonNode apps = connectedApps(jwt);
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).get("name").asText()).isEqualTo("Test client");
        assertThat(apps.get(0).get("scope").asText()).contains("pages:delete"); // latest consent wins
        assertThat(apps.get(0).get("id").asText()).isNotBlank();
    }

    @Test
    void connectedApps_areSeparatePerAppAndPerUser() throws Exception {
        signIn(registerClient(REDIRECT), null);
        signIn(registerClient(REDIRECT), null);
        assertThat(connectedApps(jwt)).hasSize(2);

        TestUsers.insert(jdbc, "other-oauth", "other@oauth.test", false, null);
        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"bob@oauth.test\",\"password\":\"securepass1\"}")).andExpect(status().isCreated());
        String bobJwt = json(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@oauth.test\",\"password\":\"securepass1\"}"))
                .andReturn().getResponse().getContentAsString()).get("jwt").asText();
        assertThat(connectedApps(bobJwt)).isEmpty();
    }

    @Test
    void revoke_cutsOffAccessAndRefreshImmediately() throws Exception {
        String clientId = registerClient(REDIRECT);
        JsonNode tokens = signIn(clientId, null);
        String access = tokens.get("access_token").asText();
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + access)).andExpect(status().isOk());

        String grantId = connectedApps(jwt).get(0).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me/connected-apps/" + grantId)
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isNoContent());

        // the access token is still within its lifetime, yet it no longer works
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
        // and the app cannot get a new one
        token("grant_type", "refresh_token", "refresh_token", tokens.get("refresh_token").asText(), "client_id", clientId)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(connectedApps(jwt)).isEmpty();
    }

    @Test
    void revoke_onlyAffectsThatApp_andTheUserCanAuthorizeItAgain() throws Exception {
        String appA = registerNamedClient("App A", REDIRECT);
        String appB = registerNamedClient("App B", REDIRECT);
        String accessA = signIn(appA, null).get("access_token").asText();
        String accessB = signIn(appB, null).get("access_token").asText();

        String grantA = null;
        for (JsonNode g : connectedApps(jwt)) if (g.get("name").asText().equals("App A")) grantA = g.get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me/connected-apps/" + grantA)
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isNoContent());

        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + accessA)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + accessB)).andExpect(status().isOk());

        // the revoked app can be authorized again from scratch
        mockMvc.perform(get("/pages").header("Authorization", "Bearer " + signIn(appA, null).get("access_token").asText()))
                .andExpect(status().isOk());
        assertThat(connectedApps(jwt)).hasSize(2);
    }

    @Test
    void revoke_someoneElsesGrant_is404() throws Exception {
        signIn(registerClient(REDIRECT), null);
        String grantId = connectedApps(jwt).get(0).get("id").asText();

        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"carol@oauth.test\",\"password\":\"securepass1\"}")).andExpect(status().isCreated());
        String carol = json(mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"carol@oauth.test\",\"password\":\"securepass1\"}"))
                .andReturn().getResponse().getContentAsString()).get("jwt").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me/connected-apps/" + grantId)
                .header("Authorization", "Bearer " + carol)).andExpect(status().isNotFound());
        assertThat(connectedApps(jwt)).hasSize(1);
    }

    @Test
    void connectedApps_needAnInteractiveSession_notAnApiKeyOrAnAppToken() throws Exception {
        String clientId = registerClient(REDIRECT);
        String access = signIn(clientId, null).get("access_token").asText();
        String grantId = connectedApps(jwt).get(0).get("id").asText();

        mockMvc.perform(get("/me/connected-apps")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/me/connected-apps").header("X-Api-Key", "pp_oauth-admin-key")).andExpect(status().isForbidden());
        mockMvc.perform(get("/me/connected-apps").header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/me/connected-apps/" + grantId)
                .header("Authorization", "Bearer " + access)).andExpect(status().isForbidden());
        assertThat(connectedApps(jwt)).hasSize(1);
    }
}
