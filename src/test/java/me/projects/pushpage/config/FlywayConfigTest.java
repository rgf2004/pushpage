package me.projects.pushpage.config;

import me.projects.pushpage.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlywayConfig builds its Flyway bean directly (Flyway.configure()...load()), scanning the
 * default location classpath:db/migration. Every migration there, including V1000+, is
 * applied regardless of which optional features are enabled.
 */
@SpringBootTest
class FlywayConfigTest extends PostgresTestSupport {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allMigrationsAreAppliedRegardlessOfFeatureFlags() {
        var appliedVersions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true", String.class);

        assertThat(appliedVersions).contains("1000", "1001");
    }
}
