package me.projects.pushpage.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.config.AuthContext;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OAuth 2.1 endpoints that let an MCP client sign a user in without an API key. See docs/oauth.md. */
@RestController
@RequestMapping("/oauth")
@CrossOrigin(origins = "*")
@Tag(name = "OAuth", description = "OAuth 2.1 authorization server for the hosted MCP sign-in endpoint")
public class OAuthController {

    public record RegisterRequest(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("redirect_uris") List<String> redirectUris) {}

    public record AuthorizeRequest(
            @JsonProperty("client_id") String clientId,
            @JsonProperty("redirect_uri") String redirectUri,
            @JsonProperty("code_challenge") String codeChallenge,
            @JsonProperty("code_challenge_method") String codeChallengeMethod,
            String scope,
            String state) {}

    private final OAuthService oauth;
    private final AuthContext authContext;

    public OAuthController(OAuthService oauth, AuthContext authContext) {
        this.oauth = oauth;
        this.authContext = authContext;
    }

    @Operation(summary = "Protected resource metadata (RFC 9728)", description = "Public. Served at /.well-known/oauth-protected-resource/mcp/signin.")
    @GetMapping("/protected-resource")
    public Map<String, Object> protectedResource() {
        return oauth.protectedResourceMetadata();
    }

    @Operation(summary = "Authorization server metadata (RFC 8414)", description = "Public. Served at /.well-known/oauth-authorization-server.")
    @GetMapping("/metadata")
    public Map<String, Object> metadata() {
        return oauth.authorizationServerMetadata();
    }

    @Operation(summary = "Register a client (RFC 7591)", description = "Public, rate-limited per IP. Public clients only (no secret).")
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        OAuthRepository.Client client = oauth.register(request.clientName(), request.redirectUris());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", client.clientId());
        body.put("client_name", client.clientName());
        body.put("redirect_uris", client.redirectUris());
        body.put("token_endpoint_auth_method", "none");
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @Operation(summary = "Look up a client for the consent page",
            description = "Public. Returns the client's name if client_id and redirect_uri match a registration; used by /oauth/authorize before it shows anything.")
    @GetMapping("/client")
    public Map<String, Object> client(@RequestParam("client_id") String clientId,
                                      @RequestParam("redirect_uri") String redirectUri) {
        OAuthRepository.Client client = oauth.requireClient(clientId, redirectUri);
        return Map.of("client_name", client.clientName());
    }

    @Operation(summary = "Approve an authorization request",
            description = "Needs a signed-in dashboard session (JWT). Returns the URL to redirect the browser to, carrying a one-time code.")
    @PostMapping("/authorize")
    public Map<String, Object> authorize(@RequestBody AuthorizeRequest request) {
        String redirect = oauth.authorize(authContext.getCurrentUser(), request.clientId(), request.redirectUri(),
                request.codeChallenge(), request.codeChallengeMethod(), request.scope(), request.state());
        return Map.of("redirect_to", redirect);
    }

    @Operation(summary = "Token endpoint", description = "Public. authorization_code (with PKCE code_verifier) or refresh_token (rotating).")
    @PostMapping(value = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> params) {
        String grant = params.get("grant_type");
        Map<String, Object> body;
        if ("authorization_code".equals(grant)) {
            body = oauth.exchangeCode(params.get("code"), params.get("redirect_uri"), params.get("client_id"),
                    params.get("code_verifier"));
        } else if ("refresh_token".equals(grant)) {
            body = oauth.refresh(params.get("refresh_token"), params.get("client_id"));
        } else {
            throw new OAuthException("unsupported_grant_type", "Use authorization_code or refresh_token");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
    }

    @ExceptionHandler(OAuthException.class)
    public ResponseEntity<Map<String, Object>> handle(OAuthException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.error());
        body.put("error_description", e.getMessage());
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(body);
    }
}
