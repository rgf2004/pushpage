package me.projects.pushpage.emailverification.repository;

import me.projects.pushpage.emailverification.ConditionalOnEmailVerificationEnabled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@ConditionalOnEmailVerificationEnabled
public class EmailVerificationRepository {

    private final JdbcTemplate jdbc;

    public EmailVerificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setVerificationToken(String userId, String token, Instant expiresAt) {
        jdbc.update(
                "UPDATE users SET verification_token = ?, verification_token_expires_at = ? WHERE id = ?",
                token, Timestamp.from(expiresAt), userId
        );
    }

    public Optional<String> findUserIdByValidToken(String token) {
        List<String> ids = jdbc.query(
                "SELECT id FROM users WHERE verification_token = ? AND verification_token_expires_at > ?",
                (rs, i) -> rs.getString("id"),
                token, Timestamp.from(Instant.now())
        );
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    public void markVerified(String userId) {
        jdbc.update(
                "UPDATE users SET email_verified = true, verification_token = NULL, verification_token_expires_at = NULL WHERE id = ?",
                userId
        );
    }

    public boolean isVerified(String userId) {
        Boolean verified = jdbc.queryForObject(
                "SELECT email_verified FROM users WHERE id = ?", Boolean.class, userId);
        return Boolean.TRUE.equals(verified);
    }

    /** Returns a userId → email_verified map for the given user IDs (for bulk enrichment). */
    public Map<String, Boolean> findVerifiedStatusByUserIds(List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = userIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        return jdbc.query(
                "SELECT id, email_verified FROM users WHERE id IN (" + placeholders + ")",
                (rs, i) -> Map.entry(rs.getString("id"), rs.getBoolean("email_verified")),
                userIds.toArray()
        ).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
