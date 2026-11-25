package me.projects.pushpage;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class PostgresTestSupport {

    /**
     * Every distinct Spring context the suite creates is cached and keeps its own connection pool open
     * against this ONE container, so the number of contexts times the pool size must stay below the
     * server's max_connections (Postgres defaults to 100: "FATAL: sorry, too many clients already").
     * Hence small pools (tests run one request at a time) and a higher server limit. Both can be
     * overridden with -Dtest.pg.max_connections=N / -Dtest.hikari.pool=N to reproduce a CI failure.
     */
    private static final int MAX_CONNECTIONS = Integer.getInteger("test.pg.max_connections", 300);
    private static final int POOL_SIZE = Integer.getInteger("test.hikari.pool", 3);

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(POOL_SIZE));
    }
}
