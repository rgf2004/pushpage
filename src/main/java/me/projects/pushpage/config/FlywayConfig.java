package me.projects.pushpage.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    /**
     * Forks that layer a second migration location (e.g. cloud deployments merging
     * classpath:db/migration with their own higher-numbered migrations) can end up
     * with a newly-added upstream migration numbered below one they've already
     * applied. Flyway's default strict-ordering validation rejects that as
     * out-of-order and refuses to start. Self-hosted deployments only ever have one
     * location applied strictly in order, so this defaults to false there.
     */
    @Bean
    public Flyway flyway(DataSource dataSource,
                          @Value("${app.flyway.out-of-order:false}") boolean outOfOrder) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .outOfOrder(outOfOrder)
                .load();
        flyway.migrate();
        return flyway;
    }
}
