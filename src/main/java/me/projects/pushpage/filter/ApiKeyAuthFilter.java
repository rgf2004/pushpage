package me.projects.pushpage.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.User;
import me.projects.pushpage.oauth.OAuthScopes;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.ApiKeyService;
import me.projects.pushpage.service.JwtClaims;
import me.projects.pushpage.service.JwtService;
import me.projects.pushpage.util.ApiKeyHasher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

@Component
@Order(1)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/health", "/config", "/swagger-ui", "/v3/api-docs", "/api-docs", "/auth/"
    );
    /** OAuth endpoints a client calls before it has any token. /oauth/authorize is not here: it needs a session. */
    private static final Set<String> OAUTH_PUBLIC_PATHS = Set.of(
            "/oauth/token", "/oauth/register", "/oauth/metadata", "/oauth/protected-resource", "/oauth/client"
    );
    private static final String OAUTH_AUTHORIZE_PATH = "/oauth/authorize";
    private static final String ADMIN_PATH_PREFIX = "/admin";
    private static final String KEY_MANAGEMENT_PATH_PREFIX = "/me/keys";
    private static final Set<String> GUEST_ALLOWED = Set.of("POST /pages");

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthContext authContext;
    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(UserRepository userRepository, JwtService jwtService, AuthContext authContext,
                            ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.authContext = authContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        String path = requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;

        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = extractApiKey(request);
        String jwt = extractJwt(request);

        if (apiKey == null && jwt == null) {
            if (GUEST_ALLOWED.contains(request.getMethod() + " " + path)) {
                chain.doFilter(request, response);
                return;
            }
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            return;
        }

        Optional<User> user;
        Set<String> scopes = null;
        String apiKeyHash = null;
        if (apiKey != null) {
            apiKeyHash = ApiKeyHasher.hash(apiKey);
            user = userRepository.findByApiKeyHash(apiKeyHash);
        } else {
            Optional<JwtClaims> parsed = jwtService.parseToken(jwt);
            user = parsed.flatMap(claims ->
                    userRepository.findById(claims.userId()).filter(claims::matchesCurrentPassword));
            if (parsed.isPresent() && parsed.get().isScoped()) {
                scopes = parsed.get().scopes();
            }
        }

        if (user.isEmpty() || !user.get().active()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired credentials");
            return;
        }

        if (scopes != null && !OAuthScopes.permits(scopes, request.getMethod(), path)) {
            // An OAuth access token only reaches the page endpoints it was granted.
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "This access token's scope does not allow this request");
            return;
        }

        if (apiKeyHash != null && path.startsWith(OAUTH_AUTHORIZE_PATH)) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "Authorizing an app needs a signed-in session, not an API key");
            return;
        }
        if (apiKeyHash != null && path.startsWith(KEY_MANAGEMENT_PATH_PREFIX)) {
            // A leaked key must not be able to mint replacement keys: key management needs a signed-in session.
            sendError(response, HttpServletResponse.SC_FORBIDDEN,
                    "API keys can only be managed from a signed-in session (the dashboard), not with an API key");
            return;
        }
        if (apiKeyHash != null) {
            apiKeyService.recordUse(apiKeyHash);
        }

        authContext.setCurrentUser(user.get());

        try {
            if (path.startsWith(ADMIN_PATH_PREFIX) && !user.get().admin()) {
                sendError(response, HttpServletResponse.SC_FORBIDDEN, "Admin access required");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            authContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        return OAUTH_PUBLIC_PATHS.contains(path) || PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private String extractApiKey(HttpServletRequest request) {
        return request.getHeader("X-Api-Key");
    }

    private String extractJwt(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
