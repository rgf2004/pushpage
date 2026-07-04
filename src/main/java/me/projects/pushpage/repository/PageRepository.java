package me.projects.pushpage.repository;

import me.projects.pushpage.model.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PageRepository {

    private final JdbcTemplate jdbc;

    public PageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String id, String title, String userId, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO pages (id, title, created_at, expires_at, user_id) VALUES (?, ?, ?, ?, ?)",
                id, title, Timestamp.from(Instant.now()), Timestamp.from(expiresAt), userId
        );
    }

    public List<Page> findAll(String baseUrl, String userId, boolean isAdmin) {
        if (isAdmin) {
            return jdbc.query(
                    "SELECT id, title, created_at, deleted_at, expires_at, user_id FROM pages WHERE deleted_at IS NULL ORDER BY created_at DESC",
                    (rs, i) -> mapPage(rs, baseUrl)
            );
        }
        return jdbc.query(
                "SELECT id, title, created_at, deleted_at, expires_at, user_id FROM pages WHERE deleted_at IS NULL AND user_id = ? ORDER BY created_at DESC",
                (rs, i) -> mapPage(rs, baseUrl),
                userId
        );
    }

    public Optional<Page> findById(String id) {
        List<Page> pages = jdbc.query(
                "SELECT id, title, created_at, deleted_at, expires_at, user_id FROM pages WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> mapPage(rs, null),
                id);
        return pages.isEmpty() ? Optional.empty() : Optional.of(pages.get(0));
    }

    public boolean existsById(String id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pages WHERE id = ? AND deleted_at IS NULL",
                Integer.class, id
        );
        return count != null && count > 0;
    }

    public List<Page> findExpired(Instant legacyCutoff) {
        Instant now = Instant.now();
        return jdbc.query(
                """
                SELECT id, title, created_at, deleted_at, expires_at, user_id FROM pages
                WHERE deleted_at IS NULL
                  AND (expires_at < ?
                       OR (expires_at IS NULL AND created_at < ?))
                """,
                (rs, i) -> mapPage(rs, null),
                Timestamp.from(now),
                Timestamp.from(legacyCutoff)
        );
    }

    public void softDeleteById(String id) {
        jdbc.update("UPDATE pages SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
    }

    public record PageStats(long count, long deletedCount, String oldestCreatedAt, String newestCreatedAt) {}

    public PageStats getStats() {
        return jdbc.queryForObject(
                """
                SELECT
                  COUNT(CASE WHEN deleted_at IS NULL THEN 1 END)     AS cnt,
                  COUNT(CASE WHEN deleted_at IS NOT NULL THEN 1 END) AS deleted_cnt,
                  MIN(CASE WHEN deleted_at IS NULL THEN created_at END) AS oldest,
                  MAX(CASE WHEN deleted_at IS NULL THEN created_at END) AS newest
                FROM pages
                """,
                (rs, i) -> {
                    Instant oldest = toInstant(rs.getTimestamp("oldest"));
                    Instant newest = toInstant(rs.getTimestamp("newest"));
                    return new PageStats(
                            rs.getLong("cnt"),
                            rs.getLong("deleted_cnt"),
                            oldest != null ? oldest.toString() : null,
                            newest != null ? newest.toString() : null
                    );
                }
        );
    }

    private Page mapPage(java.sql.ResultSet rs, String baseUrl) throws java.sql.SQLException {
        String id = rs.getString("id");
        String url = baseUrl != null ? baseUrl + "/" + id + ".html" : null;
        return new Page(
                id,
                rs.getString("title"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("deleted_at")),
                Page.toExternalExpiresAt(toInstant(rs.getTimestamp("expires_at"))),
                url,
                rs.getString("user_id")
        );
    }

    private Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
