package me.projects.pushpage.repository;

import me.projects.pushpage.model.ApiKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(ApiKey key, String keyHash) {
        jdbc.update(
                "INSERT INTO api_keys (id, user_id, name, key_hash, key_prefix, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                key.id(), key.userId(), key.name(), keyHash, key.keyPrefix(), Timestamp.from(key.createdAt()));
    }

    public List<ApiKey> findByUserId(String userId) {
        return jdbc.query(
                "SELECT id, user_id, name, key_prefix, created_at, last_used_at FROM api_keys "
                        + "WHERE user_id = ? ORDER BY created_at ASC, id ASC",
                (rs, i) -> new ApiKey(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("name"),
                        rs.getString("key_prefix"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant()),
                userId);
    }

    public long countByUserId(String userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE user_id = ?", Long.class, userId);
        return count != null ? count : 0;
    }

    /** Deletes the key only if it belongs to the user. Returns true when a key was removed. */
    public boolean deleteByIdAndUserId(String id, String userId) {
        return jdbc.update("DELETE FROM api_keys WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    public void touchLastUsed(String keyHash, Instant at) {
        jdbc.update("UPDATE api_keys SET last_used_at = ? WHERE key_hash = ?", Timestamp.from(at), keyHash);
    }
}
