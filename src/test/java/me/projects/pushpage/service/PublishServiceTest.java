package me.projects.pushpage.service;

import me.projects.pushpage.model.Page;

import java.time.Instant;
import me.projects.pushpage.model.PublishRequest;
import me.projects.pushpage.model.PublishResponse;
import me.projects.pushpage.repository.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishServiceTest {

    @Mock
    private PageRepository pageRepository;

    @Mock
    private HealthService healthService;

    @InjectMocks
    private PublishService publishService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(publishService, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(publishService, "pagesDir", tempDir.toString());
        ReflectionTestUtils.setField(publishService, "maxFileSize", DataSize.ofMegabytes(1));
        publishService.init();
    }

    @Test
    void publishPage_shouldReturnUrlContainingPageId() {
        PublishResponse response = publishService.publish(new PublishRequest("<h1>Hello</h1>", "Test"));

        assertThat(response.url()).contains(response.id());
    }

    @Test
    void publishPage_shouldStoreHtmlFileOnDisk() {
        PublishResponse response = publishService.publish(new PublishRequest("<h1>Hello</h1>", "Test"));

        assertThat(tempDir.resolve(response.id() + ".html")).exists();
    }

    @Test
    void publishPage_whenHtmlIsNull_shouldThrowBadRequest() {
        assertThatThrownBy(() -> publishService.publish(new PublishRequest(null, "Title")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTML content is required");
    }

    @Test
    void publishPage_whenHtmlIsBlank_shouldThrowBadRequest() {
        assertThatThrownBy(() -> publishService.publish(new PublishRequest("   ", "Title")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTML content is required");
    }

    @Test
    void publishPage_whenHtmlExceedsMaxSize_shouldThrow413() {
        ReflectionTestUtils.setField(publishService, "maxFileSize", DataSize.ofBytes(10));
        String oversized = "a".repeat(11);

        assertThatThrownBy(() -> publishService.publish(new PublishRequest(oversized, "Title")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    @Test
    void publishPage_whenHtmlExactlyAtLimit_shouldSucceed() {
        ReflectionTestUtils.setField(publishService, "maxFileSize", DataSize.ofBytes(10));
        String exactSize = "a".repeat(10);

        PublishResponse response = publishService.publish(new PublishRequest(exactSize, "Title"));

        assertThat(response.id()).isNotBlank();
    }

    @Test
    void deletePage_shouldRemoveFileAndDbRecord() throws Exception {
        when(pageRepository.existsById("abc123")).thenReturn(true);

        Path file = tempDir.resolve("abc123.html");
        file.toFile().createNewFile();

        publishService.deletePage("abc123");

        assertThat(file).doesNotExist();
        verify(pageRepository).deleteById("abc123");
    }

    @Test
    void deletePage_whenPageNotFound_shouldThrowNotFound() {
        when(pageRepository.existsById("missing")).thenReturn(false);

        assertThatThrownBy(() -> publishService.deletePage("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Page not found");
    }

    @Test
    void listPages_whenNoPagesExist_shouldReturnEmptyList() {
        when(pageRepository.findAll(anyString())).thenReturn(List.of());

        assertThat(publishService.listPages()).isEmpty();
    }

    @Test
    void listPages_shouldReturnAllPagesFromRepository() {
        List<Page> pages = List.of(
                new Page("id1", "Page 1", Instant.parse("2024-01-02T00:00:00Z"), "http://localhost/id1.html"),
                new Page("id2", "Page 2", Instant.parse("2024-01-01T00:00:00Z"), "http://localhost/id2.html")
        );
        when(pageRepository.findAll(anyString())).thenReturn(pages);

        assertThat(publishService.listPages()).isEqualTo(pages);
    }
}
