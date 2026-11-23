package me.projects.pushpage.repository;

import me.projects.pushpage.PostgresTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1003 replaces users.api_key_hash with the api_keys table. This runs it against data created by the
 * previous schema, in its own schema so it does not disturb the other tests sharing the container.
 */
class ApiKeysMigrationTest {

    @Test
    void existingKeysAreCarriedOverAsDefaultKeys_andTheOldColumnIsDropped() {
        DataSource dataSource = new DriverManagerDataSource(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS migtest CASCADE");

        Flyway.configure().dataSource(dataSource).schemas("migtest").defaultSchema("migtest")
                .target("1002").load().migrate();
        jdbc.update("INSERT INTO migtest.users (id, email, api_key_hash, created_at, active, admin) "
                + "VALUES ('u1', 'a@example.com', 'hash-one', now(), true, false)");
        jdbc.update("INSERT INTO migtest.users (id, email, api_key_hash, created_at, active, admin) "
                + "VALUES ('u2', 'b@example.com', 'hash-two', now(), true, true)");

        Flyway.configure().dataSource(dataSource).schemas("migtest").defaultSchema("migtest")
                .load().migrate();

        List<Map<String, Object>> keys = jdbc.queryForList(
                "SELECT user_id, name, key_hash, key_prefix, last_used_at FROM migtest.api_keys ORDER BY user_id");
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0)).containsEntry("user_id", "u1").containsEntry("name", "Default key")
                .containsEntry("key_hash", "hash-one");
        assertThat(keys.get(1)).containsEntry("user_id", "u2").containsEntry("key_hash", "hash-two");
        assertThat(keys.get(0).get("last_used_at")).isNull();
        assertThat(keys.get(0).get("key_prefix")).isNotNull();

        Integer oldColumn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'migtest' AND table_name = 'users' AND column_name = 'api_key_hash'",
                Integer.class);
        assertThat(oldColumn).isZero();

        // deleting a user removes their keys
        jdbc.update("DELETE FROM migtest.users WHERE id = 'u1'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM migtest.api_keys", Integer.class)).isEqualTo(1);

        jdbc.execute("DROP SCHEMA migtest CASCADE");
    }
}
