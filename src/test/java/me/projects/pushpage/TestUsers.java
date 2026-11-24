package me.projects.pushpage;

import me.projects.pushpage.util.ApiKeyHasher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/** Inserts users (and optionally an API key for them) directly, for integration and repository tests. */
public final class TestUsers {

    private TestUsers() {}

    /** Inserts an active user; {@code rawApiKey} (nullable) is stored hashed as one of the user's keys. */
    public static void insert(JdbcTemplate jdbc, String id, String email, boolean admin, String rawApiKey) {
        jdbc.update("INSERT INTO users (id, email, created_at, active, admin) VALUES (?, ?, ?, true, ?)",
                id, email, Timestamp.from(Instant.now()), admin);
        if (rawApiKey != null) {
            insertKey(jdbc, id, rawApiKey);
        }
    }

    public static void insertKey(JdbcTemplate jdbc, String userId, String rawApiKey) {
        jdbc.update(
                "INSERT INTO api_keys (id, user_id, name, key_hash, key_prefix, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                "k" + Math.abs(rawApiKey.hashCode()), userId, "test key", ApiKeyHasher.hash(rawApiKey),
                rawApiKey.substring(0, Math.min(8, rawApiKey.length())), Timestamp.from(Instant.now()));
    }
}
