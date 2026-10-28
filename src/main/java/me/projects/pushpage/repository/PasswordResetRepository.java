package me.projects.pushpage.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PasswordResetRepository {

    /** The user a redeemed token belonged to, and whether it was delivered by email. */
    public record RedeemedToken(String userId, boolean emailDelivered) {}

    private final JdbcTemplate jdbc;

    public PasswordResetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a new token for the user, replacing any outstanding one. */
    public void replaceToken(String userId, String tokenHash, Instant createdAt, Instant expiresAt,
                             boolean emailDelivered) {
        deleteByUserId(userId);
        jdbc.update(
                "INSERT INTO password_reset_tokens (token_hash, user_id, expires_at, created_at, email_delivered) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tokenHash, userId, Timestamp.from(expiresAt), Timestamp.from(createdAt), emailDelivered);
    }

    /** Atomically deletes and returns a valid token, so a token can be redeemed only once. */
    public Optional<RedeemedToken> redeem(String tokenHash, Instant now) {
        List<RedeemedToken> rows = jdbc.query(
                "DELETE FROM password_reset_tokens WHERE token_hash = ? AND expires_at > ? "
                        + "RETURNING user_id, email_delivered",
                (rs, i) -> new RedeemedToken(rs.getString("user_id"), rs.getBoolean("email_delivered")),
                tokenHash, Timestamp.from(now));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void deleteByUserId(String userId) {
        jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", userId);
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM password_reset_tokens WHERE expires_at <= ?", Timestamp.from(now));
    }
}
