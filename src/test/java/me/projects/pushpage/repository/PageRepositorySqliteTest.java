package me.projects.pushpage.repository;

import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;

class PageRepositorySqliteTest extends AbstractPageRepositoryTest {

    @TempDir
    static Path tempDir;

    @Override
    DataSource createTestDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        return ds;
    }
}
