package me.projects.pushpage.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.JwtService;
import me.projects.pushpage.util.ApiKeyHasher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATH_PREFIXES = Set.of(
            "/health", "/swagger-ui", "/v3/api-docs", "/api-docs", "/auth/"
    );
    private static final String ADMIN_PATH_PREFIX = "/admin";
    private static final Set<String> GUEST_ALLOWED = Set.of("POST /pages");

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final AuthContext authContext;

    public ApiKeyAuthFilter(UserRepository userRepository, JwtService jwtService, AuthContext authContext) {
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
        if (apiKey != null) {
            user = userRepository.findByApiKeyHash(ApiKeyHasher.hash(apiKey));
        } else {
            user = jwtService.validateToken(jwt).flatMap(userRepository::findById);
        }

        if (user.isEmpty() || !user.get().active()) {
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired credentials");
            return;
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
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
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
