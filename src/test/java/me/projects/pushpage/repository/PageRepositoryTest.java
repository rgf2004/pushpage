package me.projects.pushpage.repository;

import me.projects.pushpage.PostgresTestSupport;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

class PageRepositoryTest extends AbstractPageRepositoryTest {

    @Override
    DataSource createTestDataSource() {
        return new DriverManagerDataSource(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword()
        );
    }
}
