package me.projects.pushpage.repository;

import me.projects.pushpage.model.User;
import me.projects.pushpage.model.UserSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
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
                "INSERT INTO users (id, email, api_key_hash, password_hash, created_at, active, admin) VALUES (?, ?, ?, ?, ?, ?, ?)",
                user.id(), user.email(), user.apiKeyHash(), user.passwordHash(),
                Timestamp.from(user.createdAt()),
                user.active(),
                user.admin()
        );
    }

    public Optional<User> findByApiKeyHash(String apiKeyHash) {
        return queryForUser("SELECT * FROM users WHERE api_key_hash = ?", apiKeyHash);
    }

    public Optional<User> findById(String id) {
        return queryForUser("SELECT * FROM users WHERE id = ?", id);
    }

    public Optional<User> findByEmail(String email) {
        return queryForUser("SELECT * FROM users WHERE email = ?", email);
    }

    public List<UserSummary> findAll() {
        return jdbc.query(
                "SELECT id, email, created_at, active, admin FROM users ORDER BY created_at ASC",
                (rs, i) -> new UserSummary(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("active"),
                        rs.getBoolean("admin"),
                        null
                )
        );
    }

    public boolean deactivateById(String id) {
        int rows = jdbc.update("UPDATE users SET active = false WHERE id = ?", id);
        return rows > 0;
    }

    public boolean promoteById(String id) {
        int rows = jdbc.update("UPDATE users SET admin = true WHERE id = ?", id);
        return rows > 0;
    }

    public void updateApiKeyHash(String userId, String newHash) {
        jdbc.update("UPDATE users SET api_key_hash = ? WHERE id = ?", newHash, userId);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return count != null ? count : 0;
    }

    private Optional<User> queryForUser(String sql, Object... args) {
        List<User> results = jdbc.query(sql,
                (rs, i) -> new User(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("api_key_hash"),
                        rs.getString("password_hash"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("active"),
                        rs.getBoolean("admin")
                ),
                args
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
