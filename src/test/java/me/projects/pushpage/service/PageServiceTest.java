package me.projects.pushpage.service;

import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.Page;
import me.projects.pushpage.model.User;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PageServiceTest {

    static final User ADMIN_USER = new User("admin1", null, "pp_key", null, Instant.now(), true, true);
    static final User REGULAR_USER = new User("user1", null, "pp_key2", null, Instant.now(), true, false);

    @Mock
    private PageRepository pageRepository;

    @Mock
    private HealthService healthService;

    @Mock
    private AuthContext authContext;

    @Mock
    private QuotaPolicy quotaPolicy;

    @InjectMocks
    private PageService pageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(pageService, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(pageService, "pagesDir", tempDir.toString());
        ReflectionTestUtils.setField(pageService, "maxFileSize", DataSize.ofMegabytes(1));
        ReflectionTestUtils.setField(pageService, "retentionDays", 30);
        ReflectionTestUtils.setField(pageService, "guestExpirationMinutes", 30);
        pageService.init();
        lenient().when(authContext.getCurrentUser()).thenReturn(ADMIN_USER);
    }

    @Test
    void publishPage_shouldReturnUrlContainingPageId() {
        PublishResponse response = pageService.publish(new PublishRequest("<h1>Hello</h1>", "Test"));

        assertThat(response.url()).contains(response.id());
    }

    @Test
    void publishPage_shouldStoreHtmlFileOnDisk() {
        PublishResponse response = pageService.publish(new PublishRequest("<h1>Hello</h1>", "Test"));

        assertThat(tempDir.resolve(response.id() + ".html")).exists();
    }

    @Test
    void publishPage_whenHtmlIsNull_shouldThrowBadRequest() {
        assertThatThrownBy(() -> pageService.publish(new PublishRequest(null, "Title")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTML content is required");
    }

    @Test
    void publishPage_whenHtmlIsBlank_shouldThrowBadRequest() {
        assertThatThrownBy(() -> pageService.publish(new PublishRequest("   ", "Title")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("HTML content is required");
    }

    @Test
    void publishPage_whenHtmlExceedsMaxSize_shouldThrow413() {
        ReflectionTestUtils.setField(pageService, "maxFileSize", DataSize.ofBytes(10));
        String oversized = "a".repeat(11);

        assertThatThrownBy(() -> pageService.publish(new PublishRequest(oversized, "Title")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    @Test
    void publishPage_whenHtmlExactlyAtLimit_shouldSucceed() {
        ReflectionTestUtils.setField(pageService, "maxFileSize", DataSize.ofBytes(10));
        String exactSize = "a".repeat(10);

        PublishResponse response = pageService.publish(new PublishRequest(exactSize, "Title"));

        assertThat(response.id()).isNotBlank();
    }

    @Test
    void publishPage_whenTitleIsNull_extractsTitleFromHtml() {
        String html = "<!DOCTYPE html><html><head><title>From HTML</title></head><body></body></html>";

        pageService.publish(new PublishRequest(html, null));

        verify(pageRepository).save(anyString(), eq("From HTML"), anyString(), any());
    }

    @Test
    void publishPage_whenTitleIsBlank_extractsTitleFromHtml() {
        String html = "<!DOCTYPE html><html><head><title>From HTML</title></head><body></body></html>";

        pageService.publish(new PublishRequest(html, "   "));

        verify(pageRepository).save(anyString(), eq("From HTML"), anyString(), any());
    }

    @Test
    void publishPage_whenTitleNullAndNoTitleTag_usesUntitled() {
        pageService.publish(new PublishRequest("<h1>Hello</h1>", null));

        verify(pageRepository).save(anyString(), eq("Untitled"), anyString(), any());
    }

    @Test
    void publishPage_whenTitleNullAndTitleTagIsBlank_usesUntitled() {
        String html = "<!DOCTYPE html><html><head><title>   </title></head><body></body></html>";

        pageService.publish(new PublishRequest(html, null));

        verify(pageRepository).save(anyString(), eq("Untitled"), anyString(), any());
    }

    @Test
    void publishPage_explicitTitleTakesPrecedenceOverHtmlTitle() {
        String html = "<!DOCTYPE html><html><head><title>HTML Title</title></head><body></body></html>";

        pageService.publish(new PublishRequest(html, "Explicit Title"));

        verify(pageRepository).save(anyString(), eq("Explicit Title"), anyString(), any());
    }

    @Test
    void publishPage_asGuest_savesWithNullUserIdAndShortExpiry() {
        when(authContext.getCurrentUser()).thenReturn(null);

        Instant before = Instant.now();
        pageService.publish(new PublishRequest("<h1>Guest</h1>", "Guest Page"));
        Instant after = Instant.now();

        verify(pageRepository).save(
                anyString(),
                eq("Guest Page"),
                isNull(),
                argThat(exp -> !exp.isBefore(before.plusSeconds(29 * 60))
                        && !exp.isAfter(after.plusSeconds(31 * 60)))
        );
    }

    @Test
    void deletePage_shouldRemoveFileAndDbRecord() throws Exception {
        Page page = new Page("abc123", "Title", Instant.now(), null, null, null, ADMIN_USER.id());
        when(pageRepository.findById("abc123")).thenReturn(Optional.of(page));

        Path file = tempDir.resolve("abc123.html");
        file.toFile().createNewFile();

        pageService.deletePage("abc123");

        assertThat(file).doesNotExist();
        verify(pageRepository).softDeleteById("abc123");
    }

    @Test
    void deletePage_whenPageNotFound_shouldThrowNotFound() {
        when(pageRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pageService.deletePage("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Page not found");
    }

    @Test
    void deletePage_byNonOwner_shouldThrowForbidden() {
        Page page = new Page("abc123", "Title", Instant.now(), null, null, null, "someone-else");
        when(pageRepository.findById("abc123")).thenReturn(Optional.of(page));
        when(authContext.getCurrentUser()).thenReturn(REGULAR_USER);

        assertThatThrownBy(() -> pageService.deletePage("abc123"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void deletePage_adminCanDeleteAnyPage() throws Exception {
        Page page = new Page("abc123", "Title", Instant.now(), null, null, null, "someone-else");
        when(pageRepository.findById("abc123")).thenReturn(Optional.of(page));

        pageService.deletePage("abc123");

        verify(pageRepository).softDeleteById("abc123");
    }

    @Test
    void listPages_whenNoPagesExist_shouldReturnEmptyList() {
        when(pageRepository.findAll(anyString(), anyString(), anyBoolean())).thenReturn(List.of());

        assertThat(pageService.listPages()).isEmpty();
    }

    @Test
    void listPages_shouldReturnAllPagesFromRepository() {
        List<Page> pages = List.of(
                new Page("id1", "Page 1", Instant.parse("2024-01-02T00:00:00Z"), null, null, "http://localhost/id1.html", ADMIN_USER.id()),
                new Page("id2", "Page 2", Instant.parse("2024-01-01T00:00:00Z"), null, null, "http://localhost/id2.html", ADMIN_USER.id())
        );
        when(pageRepository.findAll(anyString(), anyString(), anyBoolean())).thenReturn(pages);

        assertThat(pageService.listPages()).isEqualTo(pages);
    }
}
