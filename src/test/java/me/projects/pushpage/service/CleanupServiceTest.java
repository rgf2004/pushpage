package me.projects.pushpage.service;

import me.projects.pushpage.model.Page;
import me.projects.pushpage.repository.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CleanupServiceTest {

    @Mock
    private PageRepository pageRepository;

    @InjectMocks
    private CleanupService cleanupService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(cleanupService, "retentionDays", 30);
        ReflectionTestUtils.setField(cleanupService, "pagesDir", tempDir.toString());
    }

    @Test
    void runCleanup_whenNoCandidates_shouldNotSoftDelete() {
        when(pageRepository.findOlderThan(any())).thenReturn(List.of());

        cleanupService.runCleanup();

        verify(pageRepository, never()).softDeleteById(any());
    }

    @Test
    void runCleanup_shouldDeleteFileAndSoftDeleteRecord() throws Exception {
        Page oldPage = page("abc12345", 31);
        when(pageRepository.findOlderThan(any())).thenReturn(List.of(oldPage));
        Files.createFile(tempDir.resolve("abc12345.html"));

        cleanupService.runCleanup();

        assertThat(tempDir.resolve("abc12345.html")).doesNotExist();
        verify(pageRepository).softDeleteById("abc12345");
    }

    @Test
    void runCleanup_shouldProcessAllCandidates() throws Exception {
        Page page1 = page("aaaaaaaa", 40);
        Page page2 = page("bbbbbbbb", 35);
        when(pageRepository.findOlderThan(any())).thenReturn(List.of(page1, page2));
        Files.createFile(tempDir.resolve("aaaaaaaa.html"));
        Files.createFile(tempDir.resolve("bbbbbbbb.html"));

        cleanupService.runCleanup();

        verify(pageRepository).softDeleteById("aaaaaaaa");
        verify(pageRepository).softDeleteById("bbbbbbbb");
        assertThat(tempDir.resolve("aaaaaaaa.html")).doesNotExist();
        assertThat(tempDir.resolve("bbbbbbbb.html")).doesNotExist();
    }

    @Test
    void runCleanup_whenFileAlreadyMissing_shouldStillSoftDelete() {
        Page oldPage = page("abc12345", 31);
        when(pageRepository.findOlderThan(any())).thenReturn(List.of(oldPage));
        // no file created — deleteIfExists is a no-op, not an error

        cleanupService.runCleanup();

        verify(pageRepository).softDeleteById("abc12345");
    }

    @Test
    void runCleanup_cutoffShouldReflectRetentionDays() {
        ReflectionTestUtils.setField(cleanupService, "retentionDays", 7);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        when(pageRepository.findOlderThan(cutoffCaptor.capture())).thenReturn(List.of());

        cleanupService.runCleanup();

        assertThat(cutoffCaptor.getValue())
                .isCloseTo(Instant.now().minus(7, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    private Page page(String id, int daysOld) {
        return new Page(id, "Title", Instant.now().minus(daysOld, ChronoUnit.DAYS), null, null);
    }
}
