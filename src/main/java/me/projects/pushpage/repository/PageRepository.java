package me.projects.pushpage.repository;

import me.projects.pushpage.model.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PageRepository {

    private final JdbcTemplate jdbc;

    public PageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pages (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """);
    }

    public void save(String id, String title) {
        jdbc.update(
                "INSERT INTO pages (id, title, created_at) VALUES (?, ?, ?)",
                id, title, Instant.now().toString()
        );
    }

    public List<Page> findAll(String baseUrl) {
        return jdbc.query(
                "SELECT id, title, created_at FROM pages ORDER BY created_at DESC",
                (rs, i) -> new Page(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("created_at"),
                        baseUrl + "/" + rs.getString("id") + ".html"
                )
        );
    }

    public Optional<Page> findById(String id, String baseUrl) {
        List<Page> pages = jdbc.query(
                "SELECT id, title, created_at FROM pages WHERE id = ?",
                (rs, i) -> new Page(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("created_at"),
                        baseUrl + "/" + rs.getString("id") + ".html"),
                id);
        return pages.isEmpty() ? Optional.empty() : Optional.of(pages.get(0));
    }

    public boolean existsById(String id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pages WHERE id = ?",
                Integer.class, id
        );
        return count != null && count > 0;
    }

    public void deleteById(String id) {
        jdbc.update("DELETE FROM pages WHERE id = ?", id);
    }
}
