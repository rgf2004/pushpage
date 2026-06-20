package me.projects.pushpage.repository;

import me.projects.pushpage.model.User;
import me.projects.pushpage.model.UserSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(User user) {
        jdbc.update(
                "INSERT INTO users (id, username, email, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, ?, ?, ?)",
                user.id(), user.username(), user.email(), user.apiKeyHash(),
                Timestamp.from(user.createdAt()),
                user.active(),
                user.admin()
        );
    }

    public Optional<User> findByApiKeyHash(String apiKeyHash) {
        List<User> results = jdbc.query(
                "SELECT id, username, email, api_key_hash, created_at, active, admin FROM users WHERE api_key_hash = ?",
                (rs, i) -> new User(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("api_key_hash"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("active"),
                        rs.getBoolean("admin")
                ),
                apiKeyHash
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<UserSummary> findAll() {
        return jdbc.query(
                "SELECT id, username, email, created_at, active, admin FROM users ORDER BY created_at ASC",
                (rs, i) -> new UserSummary(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("active"),
                        rs.getBoolean("admin")
                )
        );
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username
        );
        return count != null && count > 0;
    }

    public boolean deactivateById(String id) {
        int rows = jdbc.update("UPDATE users SET active = false WHERE id = ?", id);
        return rows > 0;
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count != null ? count : 0;
    }
}
