package me.projects.pushpage.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class OAuthRepository {

    public record Client(String clientId, String clientName, List<String> redirectUris) {}

    public record AuthCode(String clientId, String userId, String grantId, String redirectUri, String codeChallenge,
                           String scope, Instant expiresAt) {}

    public record RefreshToken(String clientId, String userId, String grantId, String scope, long passwordVersion,
                               Instant expiresAt) {}

    /** What a user has allowed one app to do, as listed under Connected apps. */
    public record Grant(String id, String clientName, String scope, Instant createdAt, Instant lastUsedAt) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public OAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveClient(Client client) {
        jdbc.update("INSERT INTO oauth_clients (client_id, client_name, redirect_uris, created_at) VALUES (?, ?, ?, ?)",
                client.clientId(), client.clientName(), toJson(client.redirectUris()), Timestamp.from(Instant.now()));
    }

    public Optional<Client> findClient(String clientId) {
        List<Client> found = jdbc.query(
                "SELECT client_id, client_name, redirect_uris FROM oauth_clients WHERE client_id = ?",
                (rs, i) -> new Client(rs.getString("client_id"), rs.getString("client_name"),
                        fromJson(rs.getString("redirect_uris"))),
                clientId);
        return found.stream().findFirst();
    }

    public void saveCode(String codeHash, AuthCode code) {
        jdbc.update("""
                INSERT INTO oauth_codes (code_hash, client_id, user_id, grant_id, redirect_uri, code_challenge, scope, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                codeHash, code.clientId(), code.userId(), code.grantId(), code.redirectUri(), code.codeChallenge(), code.scope(),
                Timestamp.from(code.expiresAt()));
    }

    /** Single use: the row is removed as it is read, so a replayed code finds nothing. */
    public Optional<AuthCode> consumeCode(String codeHash) {
        List<AuthCode> found = jdbc.query(
                "DELETE FROM oauth_codes WHERE code_hash = ? RETURNING client_id, user_id, grant_id, redirect_uri, code_challenge, scope, expires_at",
                (rs, i) -> new AuthCode(rs.getString("client_id"), rs.getString("user_id"), rs.getString("grant_id"),
                        rs.getString("redirect_uri"), rs.getString("code_challenge"), rs.getString("scope"),
                        rs.getTimestamp("expires_at").toInstant()),
                codeHash);
        return found.stream().findFirst();
    }

    public void saveRefreshToken(String tokenHash, RefreshToken token) {
        jdbc.update("""
                INSERT INTO oauth_refresh_tokens (token_hash, client_id, user_id, grant_id, scope, password_version, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                tokenHash, token.clientId(), token.userId(), token.grantId(), token.scope(), token.passwordVersion(),
                Timestamp.from(Instant.now()), Timestamp.from(token.expiresAt()));
    }

    /** Refresh tokens rotate: reading one removes it, and the caller issues a replacement. */
    public Optional<RefreshToken> consumeRefreshToken(String tokenHash) {
        List<RefreshToken> found = jdbc.query(
                "DELETE FROM oauth_refresh_tokens WHERE token_hash = ? RETURNING client_id, user_id, grant_id, scope, password_version, expires_at",
                (rs, i) -> new RefreshToken(rs.getString("client_id"), rs.getString("user_id"), rs.getString("grant_id"), rs.getString("scope"),
                        rs.getLong("password_version"), rs.getTimestamp("expires_at").toInstant()),
                tokenHash);
        return found.stream().findFirst();
    }

    /**
     * The grant for this user and app, created on first consent and reused when the same app is
     * authorized again (so a reconnect does not pile up duplicates). The scope is the latest consent.
     */
    public String upsertGrant(String grantId, String userId, String clientId, String scope) {
        return jdbc.queryForObject("""
                INSERT INTO oauth_grants (id, client_id, user_id, scope, created_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id, client_id) DO UPDATE SET scope = EXCLUDED.scope
                RETURNING id""", String.class, grantId, clientId, userId, scope, Timestamp.from(Instant.now()));
    }

    /** Whether the grant still exists (it does not once revoked); stamps last use at most every 5 minutes. */
    public boolean grantActive(String grantId) {
        List<Timestamp> found = jdbc.query("SELECT COALESCE(last_used_at, created_at) FROM oauth_grants WHERE id = ?",
                (rs, i) -> rs.getTimestamp(1), grantId);
        if (found.isEmpty()) {
            return false;
        }
        Instant stamped = found.get(0).toInstant();
        Instant now = Instant.now();
        if (stamped.isBefore(now.minusSeconds(300))) {
            jdbc.update("UPDATE oauth_grants SET last_used_at = ? WHERE id = ?", Timestamp.from(now), grantId);
        }
        return true;
    }

    public List<Grant> findGrants(String userId) {
        return jdbc.query("""
                SELECT g.id, c.client_name, g.scope, g.created_at, g.last_used_at
                FROM oauth_grants g JOIN oauth_clients c ON c.client_id = g.client_id
                WHERE g.user_id = ? ORDER BY g.created_at DESC""",
                (rs, i) -> new Grant(rs.getString("id"), rs.getString("client_name"), rs.getString("scope"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant()),
                userId);
    }

    /** Removes the grant, and with it the app's refresh tokens; false if it is not this user's. */
    public boolean revokeGrant(String userId, String grantId) {
        return jdbc.update("DELETE FROM oauth_grants WHERE id = ? AND user_id = ?", grantId, userId) > 0;
    }

    public int deleteExpired() {
        Timestamp now = Timestamp.from(Instant.now());
        return jdbc.update("DELETE FROM oauth_codes WHERE expires_at < ?", now)
                + jdbc.update("DELETE FROM oauth_refresh_tokens WHERE expires_at < ?", now);
    }

    private static String toJson(List<String> values) {
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fromJson(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
