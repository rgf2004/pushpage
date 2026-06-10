package me.projects.pushpage.repository;

import me.projects.pushpage.model.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PageRepositoryTest {

    private PageRepository repository;

    @BeforeEach
    void setUp() {
        // SingleConnectionDataSource reuses one connection — required for SQLite in-memory DBs
        // since each new connection gets an isolated, empty database.
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        repository = new PageRepository(new JdbcTemplate(dataSource));
        repository.init();
    }

    @Test
    void save_shouldInsertRow() {
        repository.save("abc12345", "My Page");

        assertThat(repository.existsById("abc12345")).isTrue();
    }

    @Test
    void findById_shouldReturnCorrectPage() {
        repository.save("abc12345", "My Page");

        Optional<Page> result = repository.findById("abc12345", "http://localhost");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("abc12345");
        assertThat(result.get().title()).isEqualTo("My Page");
        assertThat(result.get().url()).isEqualTo("http://localhost/abc12345.html");
    }

    @Test
    void findById_whenIdUnknown_shouldReturnEmpty() {
        Optional<Page> result = repository.findById("nonexistent", "http://localhost");

        assertThat(result).isEmpty();
    }

    @Test
    void deleteById_shouldRemoveRow() {
        repository.save("abc12345", "My Page");

        repository.deleteById("abc12345");

        assertThat(repository.existsById("abc12345")).isFalse();
    }

    @Test
    void findAll_shouldReturnAllRows() {
        repository.save("id1", "First");
        repository.save("id2", "Second");

        List<Page> pages = repository.findAll("http://localhost");

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(Page::id).containsExactlyInAnyOrder("id1", "id2");
    }
}
