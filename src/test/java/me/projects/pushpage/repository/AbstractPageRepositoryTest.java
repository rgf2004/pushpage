package me.projects.pushpage.repository;

import me.projects.pushpage.model.Page;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractPageRepositoryTest {

    static final String TEST_USER_ID = "testuser1";

    DataSource dataSource;
    JdbcTemplate jdbc;
    PageRepository repository;

    abstract DataSource createTestDataSource();

    @BeforeAll
    void migrateSchema() {
        dataSource = createTestDataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        repository = new PageRepository(jdbc);
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, 1, 0)",
                TEST_USER_ID, "testuser", "test-key-123", Timestamp.from(Instant.now())
        );
    }

    // --- save / existsById ---

    @Test
    void save_shouldInsertRow() {
        repository.save("abc12345", "My Page", TEST_USER_ID);

        assertThat(repository.existsById("abc12345")).isTrue();
    }

    // --- findById ---

    @Test
    void findById_shouldReturnCorrectPage() {
        repository.save("abc12345", "My Page", TEST_USER_ID);

        Optional<Page> result = repository.findById("abc12345");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("abc12345");
        assertThat(result.get().title()).isEqualTo("My Page");
        assertThat(result.get().userId()).isEqualTo(TEST_USER_ID);
    }

    @Test
    void findById_whenIdUnknown_shouldReturnEmpty() {
        Optional<Page> result = repository.findById("nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    void findById_whenSoftDeleted_shouldReturnEmpty() {
        repository.save("abc12345", "My Page", TEST_USER_ID);
        repository.softDeleteById("abc12345");

        assertThat(repository.findById("abc12345")).isEmpty();
    }

    // --- existsById ---

    @Test
    void existsById_whenSoftDeleted_shouldReturnFalse() {
        repository.save("abc12345", "My Page", TEST_USER_ID);
        repository.softDeleteById("abc12345");

        assertThat(repository.existsById("abc12345")).isFalse();
    }

    // --- deleteById ---

    @Test
    void deleteById_shouldRemoveRow() {
        repository.save("abc12345", "My Page", TEST_USER_ID);

        repository.deleteById("abc12345");

        assertThat(repository.existsById("abc12345")).isFalse();
    }

    // --- findAll ---

    @Test
    void findAll_adminSeesAllRows() {
        repository.save("id1", "First", TEST_USER_ID);
        repository.save("id2", "Second", TEST_USER_ID);

        List<Page> pages = repository.findAll("http://localhost", TEST_USER_ID, true);

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(Page::id).containsExactlyInAnyOrder("id1", "id2");
    }

    @Test
    void findAll_userSeesOnlyOwnPages() {
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, 1, 0)",
                "otheruser1", "other", "other-key", Timestamp.from(Instant.now())
        );
        repository.save("id1", "My page", TEST_USER_ID);
        repository.save("id2", "Other's page", "otheruser1");

        List<Page> pages = repository.findAll("http://localhost", TEST_USER_ID, false);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).id()).isEqualTo("id1");
    }

    @Test
    void findAll_shouldExcludeSoftDeletedPages() {
        repository.save("id1", "Active", TEST_USER_ID);
        repository.save("id2", "Deleted", TEST_USER_ID);
        repository.softDeleteById("id2");

        List<Page> pages = repository.findAll("http://localhost", TEST_USER_ID, true);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).id()).isEqualTo("id1");
    }

    // --- softDeleteById ---

    @Test
    void softDeleteById_shouldStampDeletedAt() {
        repository.save("abc12345", "My Page", TEST_USER_ID);

        repository.softDeleteById("abc12345");

        Timestamp deletedAt = jdbc.queryForObject(
                "SELECT deleted_at FROM pages WHERE id = ?", Timestamp.class, "abc12345");
        assertThat(deletedAt).isNotNull();
    }

    // --- findOlderThan ---

    @Test
    void findOlderThan_shouldReturnPagesBeyondCutoff() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("oldpage1", "Old 1", cutoff.minus(1, ChronoUnit.DAYS));
        insertPage("oldpage2", "Old 2", cutoff.minus(10, ChronoUnit.DAYS));
        insertPage("newpage1", "New 1", cutoff.plus(1, ChronoUnit.DAYS));

        List<Page> result = repository.findOlderThan(cutoff);

        assertThat(result).extracting(Page::id).containsExactlyInAnyOrder("oldpage1", "oldpage2");
    }

    @Test
    void findOlderThan_shouldExcludeSoftDeletedPages() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("oldpage1", "Old Active", cutoff.minus(5, ChronoUnit.DAYS));
        insertPage("oldpage2", "Old Deleted", cutoff.minus(5, ChronoUnit.DAYS));
        repository.softDeleteById("oldpage2");

        List<Page> result = repository.findOlderThan(cutoff);

        assertThat(result).extracting(Page::id).containsExactly("oldpage1");
    }

    @Test
    void findOlderThan_whenNoCandidates_shouldReturnEmptyList() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("newpage1", "New", cutoff.plus(1, ChronoUnit.DAYS));

        assertThat(repository.findOlderThan(cutoff)).isEmpty();
    }

    void insertPage(String id, String title, Instant createdAt) {
        jdbc.update("INSERT INTO pages (id, title, created_at, user_id) VALUES (?, ?, ?, ?)",
                id, title, Timestamp.from(createdAt), TEST_USER_ID);
    }
}
