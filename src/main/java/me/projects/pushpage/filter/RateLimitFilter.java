package me.projects.pushpage.filter;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.constants.AppHeaders;
import me.projects.pushpage.model.User;
import me.projects.pushpage.service.RateLimitService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String PUBLISH_PATH = "/pages";
    private static final String PUBLISH_METHOD = "POST";

    private final AuthContext authContext;
    private final RateLimitService rateLimitService;

    public RateLimitFilter(AuthContext authContext, RateLimitService rateLimitService) {
        this.authContext = authContext;
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!isRateLimitedRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        User currentUser = authContext.getCurrentUser();

        if (currentUser != null && currentUser.admin()) {
            addRateLimitHeaders(response, rateLimitService.getUserRequestsPerMinute(), rateLimitService.getUserRequestsPerMinute(), 0);
            chain.doFilter(request, response);
            return;
        }

        boolean isGuest = currentUser == null;
        String key = isGuest
                ? "ip:" + resolveClientIp(request)
                : "user:" + currentUser.id();
        int limit = isGuest
                ? rateLimitService.getGuestRequestsPerMinute()
                : rateLimitService.getUserRequestsPerMinute();

        ConsumptionProbe probe = rateLimitService.tryConsume(key, limit);
        long remaining = probe.getRemainingTokens();
        long resetEpoch = Instant.now().plusNanos(probe.getNanosToWaitForRefill()).getEpochSecond();

        addRateLimitHeaders(response, limit, remaining, resetEpoch);

        if (!probe.isConsumed()) {
            long retryAfterSeconds = (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L;
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isRateLimitedRequest(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        return PUBLISH_METHOD.equalsIgnoreCase(request.getMethod()) && path.equals(PUBLISH_PATH);
    }

    private void addRateLimitHeaders(HttpServletResponse response, long limit, long remaining, long resetEpoch) {
        response.setHeader(AppHeaders.X_RATELIMIT_LIMIT, String.valueOf(limit));
        response.setHeader(AppHeaders.X_RATELIMIT_REMAINING, String.valueOf(remaining));
        response.setHeader(AppHeaders.X_RATELIMIT_RESET, String.valueOf(resetEpoch));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
