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
import static org.assertj.core.api.Assertions.within;

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
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, true, false)",
                TEST_USER_ID, "testuser", "test-key-123", Timestamp.from(Instant.now())
        );
    }

    // --- save / existsById ---

    @Test
    void save_shouldInsertRow() {
        repository.save("abc12345", "My Page", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));

        assertThat(repository.existsById("abc12345")).isTrue();
    }

    // --- findById ---

    @Test
    void findById_shouldReturnCorrectPage() {
        repository.save("abc12345", "My Page", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));

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
        repository.save("abc12345", "My Page", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));
        repository.softDeleteById("abc12345");

        assertThat(repository.findById("abc12345")).isEmpty();
    }

    // --- existsById ---

    @Test
    void existsById_whenSoftDeleted_shouldReturnFalse() {
        repository.save("abc12345", "My Page", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));
        repository.softDeleteById("abc12345");

        assertThat(repository.existsById("abc12345")).isFalse();
    }

    // --- findAll ---

    @Test
    void findAll_adminSeesAllRows() {
        repository.save("id1", "First", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));
        repository.save("id2", "Second", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));

        List<Page> pages = repository.findAll("http://localhost", TEST_USER_ID, true);

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(Page::id).containsExactlyInAnyOrder("id1", "id2");
    }

    @Test
    void findAll_userSeesOnlyOwnPages() {
        jdbc.update(
                "INSERT INTO users (id, username, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, true, false)",
                "otheruser1", "other", "other-key", Timestamp.from(Instant.now())
        );
        repository.save("id1", "My page", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));
        repository.save("id2", "Other's page", "otheruser1", Instant.now().plus(30, ChronoUnit.DAYS));

        List<Page> pages = repository.findAll("http://localhost", TEST_USER_ID, false);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).id()).isEqualTo("id1");
    }

    @Test
    void findAll_shouldExcludeSoftDeletedPages() {
        repository.save("id1", "Active", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));
        repository.save("id2", "Deleted", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));
        repository.softDeleteById("id2");

        List<Page> pages = repository.findAll("http://localhost", TEST_USER_ID, true);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).id()).isEqualTo("id1");
    }

    // --- softDeleteById ---

    @Test
    void softDeleteById_shouldStampDeletedAt() {
        repository.save("abc12345", "My Page", TEST_USER_ID, Instant.now().plus(30, ChronoUnit.DAYS));

        repository.softDeleteById("abc12345");

        Timestamp deletedAt = jdbc.queryForObject(
                "SELECT deleted_at FROM pages WHERE id = ?", Timestamp.class, "abc12345");
        assertThat(deletedAt).isNotNull();
    }

    // --- save should populate expires_at ---

    @Test
    void save_shouldPersistExpiresAt() {
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        repository.save("abc12345", "My Page", TEST_USER_ID, expiresAt);

        Timestamp stored = jdbc.queryForObject(
                "SELECT expires_at FROM pages WHERE id = ?", Timestamp.class, "abc12345");
        assertThat(stored).isNotNull();
        assertThat(stored.toInstant()).isCloseTo(expiresAt, within(1, ChronoUnit.SECONDS));
    }

    // --- findExpired ---

    @Test
    void findExpired_shouldReturnPagesWhereExpiresAtHasPassed() {
        Instant legacyCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("exp1", "Expired 1", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.HOURS));
        insertPage("exp2", "Expired 2", Instant.now().minus(60, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
        insertPage("fut1", "Future", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(29, ChronoUnit.DAYS));

        List<Page> result = repository.findExpired(legacyCutoff);

        assertThat(result).extracting(Page::id).containsExactlyInAnyOrder("exp1", "exp2");
    }

    @Test
    void findExpired_legacyRowsWithoutExpiresAt_shouldBePickedUpByCreatedAtFallback() {
        Instant legacyCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("legacy1", "Legacy Old", legacyCutoff.minus(1, ChronoUnit.DAYS), null);
        insertPage("legacy2", "Legacy New", legacyCutoff.plus(1, ChronoUnit.DAYS), null);

        List<Page> result = repository.findExpired(legacyCutoff);

        assertThat(result).extracting(Page::id).containsExactly("legacy1");
    }

    @Test
    void findExpired_shouldExcludeSoftDeletedPages() {
        Instant legacyCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("exp1", "Active expired", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.HOURS));
        insertPage("exp2", "Soft deleted", Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.HOURS));
        repository.softDeleteById("exp2");

        List<Page> result = repository.findExpired(legacyCutoff);

        assertThat(result).extracting(Page::id).containsExactly("exp1");
    }

    @Test
    void findExpired_whenNoCandidates_shouldReturnEmptyList() {
        Instant legacyCutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertPage("fut1", "Future", Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));

        assertThat(repository.findExpired(legacyCutoff)).isEmpty();
    }

    void insertPage(String id, String title, Instant createdAt, Instant expiresAt) {
        jdbc.update("INSERT INTO pages (id, title, created_at, expires_at, user_id) VALUES (?, ?, ?, ?, ?)",
                id, title, Timestamp.from(createdAt),
                expiresAt != null ? Timestamp.from(expiresAt) : null,
                TEST_USER_ID);
    }
}
