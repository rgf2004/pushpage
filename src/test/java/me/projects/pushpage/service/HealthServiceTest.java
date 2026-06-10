package me.projects.pushpage.service;

import me.projects.pushpage.model.HealthResponse;
import me.projects.pushpage.repository.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthServiceTest {

    @Mock
    private PageRepository pageRepository;

    @Mock
    private BuildProperties buildProperties;

    @InjectMocks
    private HealthService healthService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(healthService, "pagesDir", tempDir.toString());
        ReflectionTestUtils.setField(healthService, "startTime", Instant.now().minusSeconds(120));
        when(buildProperties.getVersion()).thenReturn("0.3.0-SNAPSHOT");
    }

    @Test
    void getHealth_returnsStatusUp_whenSubsystemsAreHealthy() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(5, 2, "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("UP");
    }

    @Test
    void getHealth_returnsCorrectVersion() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(0, 0, null, null));

        HealthResponse response = healthService.getHealth();

        assertThat(response.version()).isEqualTo("0.3.0-SNAPSHOT");
    }

    @Test
    void getHealth_returnsUptimeSeconds() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(0, 0, null, null));

        HealthResponse response = healthService.getHealth();

        assertThat(response.uptimeSeconds()).isGreaterThanOrEqualTo(120);
    }

    @Test
    void getHealth_returnsTotalPagesAndDeletedPages() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(7, 3, "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.livePages()).isEqualTo(7);
        assertThat(response.deletedPages()).isEqualTo(3);
    }

    @Test
    void getHealth_returnsOldestAndNewestPage_whenPagesExist() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(2, 0, "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.oldestPage()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(response.newestPage()).isEqualTo("2026-06-01T00:00:00Z");
    }

    @Test
    void getHealth_omitsOldestAndNewest_whenNoPagesExist() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(0, 0, null, null));

        HealthResponse response = healthService.getHealth();

        assertThat(response.oldestPage()).isNull();
        assertThat(response.newestPage()).isNull();
    }

    @Test
    void getHealth_returnsStorageInfo_withUsedAndFreeBytes() throws IOException {
        Files.writeString(tempDir.resolve("page1.html"), "<h1>Hello</h1>");
        Files.writeString(tempDir.resolve("page2.html"), "<h1>World</h1>");
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(2, 0, null, null));

        HealthResponse response = healthService.getHealth();

        assertThat(response.storage().usedBytes()).isPositive();
        assertThat(response.storage().usedHuman()).isNotBlank();
        assertThat(response.storage().freeBytes()).isPositive();
        assertThat(response.storage().freeHuman()).isNotBlank();
    }

    @Test
    void getHealth_countsOnlyHtmlFiles_forUsedBytes() throws IOException {
        Files.writeString(tempDir.resolve("page1.html"), "a".repeat(1000));
        Files.writeString(tempDir.resolve("other.txt"), "b".repeat(5000));
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(1, 0, null, null));
        healthService.invalidateCache();

        HealthResponse response = healthService.getHealth();

        assertThat(response.storage().usedBytes()).isEqualTo(1000);
    }

    @Test
    void getHealth_returnsStatusDown_whenRepositoryThrows() {
        when(pageRepository.getStats()).thenThrow(new RuntimeException("DB unavailable"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.status()).isEqualTo("DOWN");
    }

    @Test
    void getHealth_stillReturnsVersion_whenDown() {
        when(pageRepository.getStats()).thenThrow(new RuntimeException("DB unavailable"));

        HealthResponse response = healthService.getHealth();

        assertThat(response.version()).isEqualTo("0.3.0-SNAPSHOT");
    }

    @Test
    void invalidateCache_causesFreshStatsOnNextCall() {
        when(pageRepository.getStats())
                .thenReturn(new PageRepository.PageStats(1, 0, null, null))
                .thenReturn(new PageRepository.PageStats(5, 0, null, null));

        healthService.getHealth();
        healthService.invalidateCache();
        HealthResponse second = healthService.getHealth();

        assertThat(second.livePages()).isEqualTo(5);
        verify(pageRepository, times(2)).getStats();
    }

    @Test
    void getHealth_usesCachedStats_withinTtl() {
        when(pageRepository.getStats()).thenReturn(new PageRepository.PageStats(1, 0, null, null));

        healthService.getHealth();
        healthService.getHealth();
        healthService.getHealth();

        verify(pageRepository, times(1)).getStats();
    }
}
