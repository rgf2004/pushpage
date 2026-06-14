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

    public void save(String id, String title) {
        jdbc.update(
                "INSERT INTO pages (id, title, created_at) VALUES (?, ?, ?)",
                id, title, Timestamp.from(Instant.now())
        );
    }

    public List<Page> findAll(String baseUrl) {
        return jdbc.query(
                "SELECT id, title, created_at FROM pages WHERE deleted_at IS NULL ORDER BY created_at DESC",
                (rs, i) -> new Page(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        baseUrl + "/" + rs.getString("id") + ".html"
                )
        );
    }

    public Optional<Page> findById(String id) {
        List<Page> pages = jdbc.query(
                "SELECT id, title, created_at FROM pages WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new Page(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        null),
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

    public void deleteById(String id) {
        jdbc.update("DELETE FROM pages WHERE id = ?", id);
    }

    public List<Page> findOlderThan(Instant cutoff) {
        return jdbc.query(
                "SELECT id, title, created_at FROM pages WHERE deleted_at IS NULL AND created_at < ?",
                (rs, i) -> new Page(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        null),
                Timestamp.from(cutoff)
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
                    Timestamp oldest = rs.getTimestamp("oldest");
                    Timestamp newest = rs.getTimestamp("newest");
                    return new PageStats(
                            rs.getLong("cnt"),
                            rs.getLong("deleted_cnt"),
                            oldest != null ? oldest.toInstant().toString() : null,
                            newest != null ? newest.toInstant().toString() : null
                    );
                }
        );
    }
}
