package me.projects.pushpage.oauth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.config.AuthContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The apps (MCP clients) a user has allowed through OAuth, and a way to remove one. Session only. */
@RestController
@RequestMapping("/me/connected-apps")
@Tag(name = "OAuth", description = "OAuth 2.1 authorization server for the hosted MCP sign-in endpoint")
public class ConnectedAppsController {

    private final OAuthService oauth;
    private final AuthContext authContext;

    public ConnectedAppsController(OAuthService oauth, AuthContext authContext) {
        this.oauth = oauth;
        this.authContext = authContext;
    }

    @Operation(summary = "List connected apps",
            description = "Apps you allowed to use your account through the browser sign-in. Needs a signed-in session (JWT), not an API key.")
    @GetMapping
    public List<Map<String, Object>> list() {
        return oauth.grants(authContext.getCurrentUser().id()).stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.id());
            m.put("name", g.clientName());
            m.put("scope", g.scope());
            m.put("created_at", g.createdAt().toString());
            m.put("last_used_at", g.lastUsedAt() == null ? null : g.lastUsedAt().toString());
            return m;
        }).toList();
    }

    @Operation(summary = "Revoke a connected app",
            description = "Removes the app's access immediately: its access tokens stop working and its refresh tokens are deleted. 404 if it is not yours.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        boolean removed = oauth.revokeGrant(authContext.getCurrentUser().id(), id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
