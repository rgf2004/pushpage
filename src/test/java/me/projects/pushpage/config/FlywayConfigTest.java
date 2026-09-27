package me.projects.pushpage.config;

import me.projects.pushpage.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlywayConfig builds its Flyway bean directly (Flyway.configure()...load()) and never
 * reads spring.flyway.* properties. Its default location, classpath:db/migration, is
 * scanned recursively, so migrations under db/migration/cloud are picked up without any
 * extra configuration and regardless of active profile.
 */
@SpringBootTest
class FlywayConfigTest extends PostgresTestSupport {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void cloudMigrationsUnderDefaultSubdirectoryAreAppliedWithoutSpringFlywayLocations() {
        var appliedVersions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true", String.class);

        assertThat(appliedVersions).contains("1000", "1001");
    }
}
