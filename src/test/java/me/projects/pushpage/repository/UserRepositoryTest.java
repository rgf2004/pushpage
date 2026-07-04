package me.projects.pushpage.repository;

import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.model.UserSummary;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRepositoryTest {

    DataSource dataSource;
    JdbcTemplate jdbc;
    UserRepository repository;

    @BeforeAll
    void migrateSchema() {
        dataSource = new DriverManagerDataSource(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        repository = new UserRepository(jdbc);
        jdbc.execute("DELETE FROM pages");
        jdbc.execute("DELETE FROM users");
    }

    @Test
    void findAll_userWithNoPages_returnsZeroActivePageCount() {
        insertUser("user1", "user1@example.com");

        List<UserSummary> result = repository.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).activePageCount()).isZero();
    }

    @Test
    void findAll_countsLiveAndLegacyNullExpiryPages_excludesExpiredAndDeleted() {
        insertUser("user1", "user1@example.com");
        insertPage("p1", "user1", null); // legacy pre-expires_at row — still live until swept
        insertPage("p2", "user1", Instant.now().plus(30, ChronoUnit.DAYS)); // active
        insertPage("p3", "user1", Instant.now().minus(1, ChronoUnit.DAYS)); // expired
        insertDeletedPage("p4", "user1", Instant.now().plus(30, ChronoUnit.DAYS)); // soft-deleted

        List<UserSummary> result = repository.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).activePageCount()).isEqualTo(2);
    }

    @Test
    void findAll_multipleUsers_eachGetsOwnCount() {
        insertUser("user1", "user1@example.com");
        insertUser("user2", "user2@example.com");
        insertPage("p1", "user1", Instant.now().plus(30, ChronoUnit.DAYS));
        insertPage("p2", "user2", Instant.now().plus(30, ChronoUnit.DAYS));
        insertPage("p3", "user2", Instant.now().plus(30, ChronoUnit.DAYS));

        List<UserSummary> result = repository.findAll();

        assertThat(result).extracting(UserSummary::id, UserSummary::activePageCount)
                .containsExactlyInAnyOrder(tuple("user1", 1L), tuple("user2", 2L));
    }

    @Test
    void findAll_planAndEmailVerifiedAreAlwaysNull() {
        insertUser("user1", "user1@example.com");

        List<UserSummary> result = repository.findAll();

        assertThat(result.get(0).plan()).isNull();
        assertThat(result.get(0).emailVerified()).isNull();
    }

    void insertUser(String id, String email) {
        jdbc.update(
                "INSERT INTO users (id, email, api_key_hash, created_at, active, admin) VALUES (?, ?, ?, ?, true, false)",
                id, email, "hash-" + id, Timestamp.from(Instant.now())
        );
    }

    void insertPage(String id, String userId, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO pages (id, title, created_at, expires_at, user_id) VALUES (?, ?, ?, ?, ?)",
                id, "Title", Timestamp.from(Instant.now()),
                expiresAt != null ? Timestamp.from(expiresAt) : null,
                userId
        );
    }

    void insertDeletedPage(String id, String userId, Instant expiresAt) {
        insertPage(id, userId, expiresAt);
        jdbc.update("UPDATE pages SET deleted_at = ? WHERE id = ?", Timestamp.from(Instant.now()), id);
    }
}
