package me.projects.pushpage.oauth;

import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.JwtClaims;
import me.projects.pushpage.service.JwtService;
import me.projects.pushpage.util.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth 2.1 authorization server for the hosted MCP endpoint: public clients (no secret),
 * authorization code with PKCE (S256 only), short-lived access tokens, rotating refresh tokens.
 * Identity is the existing email/password account; access tokens are JWTs carrying a scope.
 */
@Service
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    private static final long CODE_TTL_SECONDS = 120;
    private static final int MAX_REDIRECT_URIS = 5;
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of("javascript", "data", "file", "vbscript", "about", "blob");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final OAuthRepository repository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.server-url}")
    private String serverUrl;

    @Value("${app.oauth.access-token-minutes:60}")
    private long accessTokenMinutes;

    @Value("${app.oauth.refresh-token-days:30}")
    private long refreshTokenDays;

    public OAuthService(OAuthRepository repository, UserRepository userRepository, JwtService jwtService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    private String base() {
        return serverUrl.replaceAll("/+$", "");
    }

    public Map<String, Object> protectedResourceMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", base() + "/mcp/signin");
        m.put("authorization_servers", List.of(base()));
        m.put("scopes_supported", OAuthScopes.SUPPORTED);
        m.put("bearer_methods_supported", List.of("header"));
        m.put("resource_name", "pushpage");
        return m;
    }

    public Map<String, Object> authorizationServerMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", base());
        m.put("authorization_endpoint", base() + "/oauth/authorize");
        m.put("token_endpoint", base() + "/api/oauth/token");
        m.put("registration_endpoint", base() + "/api/oauth/register");
        m.put("scopes_supported", OAuthScopes.SUPPORTED);
        m.put("response_types_supported", List.of("code"));
        m.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        m.put("code_challenge_methods_supported", List.of("S256"));
        m.put("token_endpoint_auth_methods_supported", List.of("none"));
        return m;
    }

    // ── Dynamic client registration (RFC 7591) ───────────────────────────────

    public OAuthRepository.Client register(String clientName, List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new OAuthException("invalid_redirect_uri", "Provide between 1 and " + MAX_REDIRECT_URIS + " redirect_uris");
        }
        redirectUris.forEach(OAuthService::requireValidRedirectUri);
        String name = clientName == null || clientName.isBlank() ? "MCP client" : clientName.strip();
        if (name.length() > 100) name = name.substring(0, 100);
        OAuthRepository.Client client = new OAuthRepository.Client(UUID.randomUUID().toString(), name, List.copyOf(redirectUris));
        repository.saveClient(client);
        return client;
    }

    static void requireValidRedirectUri(String uri) {
        if (uri == null || uri.isBlank() || uri.length() > 2048) {
            throw new OAuthException("invalid_redirect_uri", "Invalid redirect URI");
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (Exception e) {
            throw new OAuthException("invalid_redirect_uri", "Redirect URI is not a valid URI");
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        if (scheme.isEmpty() || FORBIDDEN_SCHEMES.contains(scheme) || parsed.getFragment() != null) {
            throw new OAuthException("invalid_redirect_uri", "Redirect URI scheme or fragment is not allowed");
        }
        if (scheme.equals("http") && !LOOPBACK_HOSTS.contains(String.valueOf(parsed.getHost()).toLowerCase())) {
            throw new OAuthException("invalid_redirect_uri", "http redirect URIs are only allowed for localhost");
        }
        if ((scheme.equals("http") || scheme.equals("https")) && parsed.getHost() == null) {
            throw new OAuthException("invalid_redirect_uri", "Redirect URI needs a host");
        }
    }

    /** The client, if the redirect URI is one it registered (exact match). */
    public OAuthRepository.Client requireClient(String clientId, String redirectUri) {
        OAuthRepository.Client client = clientId == null ? null : repository.findClient(clientId).orElse(null);
        if (client == null) {
            throw new OAuthException("invalid_client", "Unknown client_id", 401);
        }
        if (redirectUri == null || !client.redirectUris().contains(redirectUri)) {
            throw new OAuthException("invalid_request", "redirect_uri does not match a registered URI");
        }
        return client;
    }

    // ── Authorization (user consent) ──────────────────────────────────────────

    /** Issues a one-time code for the signed-in user and returns the URL to send the browser back to. */
    public String authorize(User user, String clientId, String redirectUri, String codeChallenge,
                            String codeChallengeMethod, String scope, String state) {
        requireClient(clientId, redirectUri);
        if (codeChallenge == null || codeChallenge.isBlank() || !"S256".equals(codeChallengeMethod)) {
            throw new OAuthException("invalid_request", "PKCE with code_challenge_method=S256 is required");
        }
        Set<String> scopes = OAuthScopes.parse(scope);
        if (scopes.isEmpty()) {
            throw new OAuthException("invalid_scope", "No supported scope requested");
        }
        String code = newSecret();
        repository.saveCode(ApiKeyHasher.hash(code), new OAuthRepository.AuthCode(clientId, user.id(), redirectUri,
                codeChallenge, OAuthScopes.format(scopes), Instant.now().plusSeconds(CODE_TTL_SECONDS)));
        UriComponentsBuilder redirect = UriComponentsBuilder.fromUriString(redirectUri).queryParam("code", code);
        if (state != null && !state.isEmpty()) {
            redirect.queryParam("state", state);
        }
        return redirect.build().encode().toUriString();
    }

    // ── Token endpoint ────────────────────────────────────────────────────────

    public Map<String, Object> exchangeCode(String code, String redirectUri, String clientId, String codeVerifier) {
        if (code == null || codeVerifier == null || clientId == null) {
            throw new OAuthException("invalid_request", "code, code_verifier and client_id are required");
        }
        OAuthRepository.AuthCode stored = repository.consumeCode(ApiKeyHasher.hash(code))
                .orElseThrow(() -> new OAuthException("invalid_grant", "Invalid or already used code"));
        if (stored.expiresAt().isBefore(Instant.now())
                || !stored.clientId().equals(clientId)
                || !stored.redirectUri().equals(redirectUri)
                || !pkceMatches(codeVerifier, stored.codeChallenge())) {
            throw new OAuthException("invalid_grant", "Invalid, expired or mismatched code");
        }
        User user = activeUser(stored.userId());
        return issueTokens(user, stored.clientId(), stored.scope());
    }

    public Map<String, Object> refresh(String refreshToken, String clientId) {
        if (refreshToken == null || clientId == null) {
            throw new OAuthException("invalid_request", "refresh_token and client_id are required");
        }
        OAuthRepository.RefreshToken stored = repository.consumeRefreshToken(ApiKeyHasher.hash(refreshToken))
                .orElseThrow(() -> new OAuthException("invalid_grant", "Invalid or already used refresh token"));
        if (stored.expiresAt().isBefore(Instant.now()) || !stored.clientId().equals(clientId)) {
            throw new OAuthException("invalid_grant", "Invalid or expired refresh token");
        }
        User user = activeUser(stored.userId());
        if (stored.passwordVersion() != JwtClaims.passwordVersionOf(user)) {
            throw new OAuthException("invalid_grant", "The password changed since this token was issued");
        }
        return issueTokens(user, stored.clientId(), stored.scope());
    }

    private User activeUser(String userId) {
        return userRepository.findById(userId).filter(User::active)
                .orElseThrow(() -> new OAuthException("invalid_grant", "The account is not available"));
    }

    private Map<String, Object> issueTokens(User user, String clientId, String scope) {
        Set<String> scopes = OAuthScopes.parse(scope);
        String refreshToken = newSecret();
        repository.saveRefreshToken(ApiKeyHasher.hash(refreshToken), new OAuthRepository.RefreshToken(clientId, user.id(),
                scope, JwtClaims.passwordVersionOf(user), Instant.now().plus(refreshTokenDays, ChronoUnit.DAYS)));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", jwtService.generateAccessToken(user, scopes, clientId, accessTokenMinutes));
        response.put("token_type", "Bearer");
        response.put("expires_in", accessTokenMinutes * 60);
        response.put("refresh_token", refreshToken);
        response.put("scope", scope);
        return response;
    }

    private static boolean pkceMatches(String verifier, String challenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanupExpired() {
        int removed = repository.deleteExpired();
        if (removed > 0) {
            log.info("OAuth cleanup: removed {} expired codes/refresh tokens", removed);
        }
    }
}
