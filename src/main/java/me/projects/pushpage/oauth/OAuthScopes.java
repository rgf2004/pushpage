package me.projects.pushpage.oauth;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The scopes an OAuth access token can carry, and which API calls each one allows. */
public final class OAuthScopes {

    public static final String PUBLISH = "pages:publish";
    public static final String READ = "pages:read";
    public static final String UPDATE = "pages:update";
    public static final String DELETE = "pages:delete";
    public static final List<String> SUPPORTED = List.of(PUBLISH, UPDATE, READ, DELETE);

    private OAuthScopes() {}

    /** Parses a space-separated scope string; blank means everything supported. Unknown scopes are dropped. */
    public static Set<String> parse(String scope) {
        if (scope == null || scope.isBlank()) {
            return new LinkedHashSet<>(SUPPORTED);
        }
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(scope.trim().split("\\s+")).filter(SUPPORTED::contains).forEach(out::add);
        return out;
    }

    public static String format(Set<String> scopes) {
        return String.join(" ", scopes);
    }

    /**
     * Whether a token with these scopes may make this call. OAuth tokens only ever reach the page
     * endpoints (and read their own identity): never admin, API-key management or consent.
     */
    public static boolean permits(Set<String> scopes, String method, String path) {
        if (method.equals("GET") && path.equals("/me")) return true;
        if (path.equals("/pages")) {
            if (method.equals("POST")) return scopes.contains(PUBLISH);
            if (method.equals("GET")) return scopes.contains(READ);
        }
        // Replacing a page's content has its own scope: grants made before it existed do not carry it.
        if (path.startsWith("/pages/") && method.equals("PUT")) return scopes.contains(UPDATE);
        if (path.startsWith("/pages/") && method.equals("DELETE")) return scopes.contains(DELETE);
        return false;
    }
}
