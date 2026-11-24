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

    public record AuthCode(String clientId, String userId, String redirectUri, String codeChallenge,
                           String scope, Instant expiresAt) {}

    public record RefreshToken(String clientId, String userId, String scope, long passwordVersion,
                               Instant expiresAt) {}

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
                INSERT INTO oauth_codes (code_hash, client_id, user_id, redirect_uri, code_challenge, scope, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                codeHash, code.clientId(), code.userId(), code.redirectUri(), code.codeChallenge(), code.scope(),
                Timestamp.from(code.expiresAt()));
    }

    /** Single use: the row is removed as it is read, so a replayed code finds nothing. */
    public Optional<AuthCode> consumeCode(String codeHash) {
        List<AuthCode> found = jdbc.query(
                "DELETE FROM oauth_codes WHERE code_hash = ? RETURNING client_id, user_id, redirect_uri, code_challenge, scope, expires_at",
                (rs, i) -> new AuthCode(rs.getString("client_id"), rs.getString("user_id"),
                        rs.getString("redirect_uri"), rs.getString("code_challenge"), rs.getString("scope"),
                        rs.getTimestamp("expires_at").toInstant()),
                codeHash);
        return found.stream().findFirst();
    }

    public void saveRefreshToken(String tokenHash, RefreshToken token) {
        jdbc.update("""
                INSERT INTO oauth_refresh_tokens (token_hash, client_id, user_id, scope, password_version, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                tokenHash, token.clientId(), token.userId(), token.scope(), token.passwordVersion(),
                Timestamp.from(Instant.now()), Timestamp.from(token.expiresAt()));
    }

    /** Refresh tokens rotate: reading one removes it, and the caller issues a replacement. */
    public Optional<RefreshToken> consumeRefreshToken(String tokenHash) {
        List<RefreshToken> found = jdbc.query(
                "DELETE FROM oauth_refresh_tokens WHERE token_hash = ? RETURNING client_id, user_id, scope, password_version, expires_at",
                (rs, i) -> new RefreshToken(rs.getString("client_id"), rs.getString("user_id"), rs.getString("scope"),
                        rs.getLong("password_version"), rs.getTimestamp("expires_at").toInstant()),
                tokenHash);
        return found.stream().findFirst();
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
