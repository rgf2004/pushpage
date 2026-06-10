package me.projects.pushpage.service;

import me.projects.pushpage.model.HealthResponse;
import me.projects.pushpage.repository.PageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
public class HealthService {

    private static final long CACHE_TTL_MS = 30_000;

    private final PageRepository pageRepository;
    private final BuildProperties buildProperties;

    @Value("${app.pages-dir}")
    private String pagesDir;

    private Instant startTime;

    private final AtomicReference<CachedStats> cachedStats = new AtomicReference<>();

    public void invalidateCache() {
        cachedStats.set(null);
    }

    public HealthService(PageRepository pageRepository, BuildProperties buildProperties) {
        this.pageRepository = pageRepository;
        this.buildProperties = buildProperties;
    }

    @PostConstruct
    void init() {
        startTime = Instant.now();
    }

    public HealthResponse getHealth() {
        try {
            CachedStats stats = refreshIfStale();
            PageRepository.PageStats pageStats = stats.pageStats();
            String oldest = pageStats.count() > 0 ? pageStats.oldestCreatedAt() : null;
            String newest = pageStats.count() > 0 ? pageStats.newestCreatedAt() : null;

            HealthResponse.StorageInfo storage = new HealthResponse.StorageInfo(
                    stats.usedBytes(),
                    humanReadable(stats.usedBytes()),
                    stats.freeBytes(),
                    humanReadable(stats.freeBytes())
            );

            return new HealthResponse(
                    "UP",
                    buildProperties.getVersion(),
                    Instant.now().getEpochSecond() - startTime.getEpochSecond(),
                    pageStats.count(),
                    oldest,
                    newest,
                    storage
            );
        } catch (Exception e) {
            return new HealthResponse("DOWN", buildProperties.getVersion(),
                    Instant.now().getEpochSecond() - startTime.getEpochSecond(),
                    0, null, null, null);
        }
    }

    private CachedStats refreshIfStale() throws IOException {
        CachedStats current = cachedStats.get();
        if (current != null && System.currentTimeMillis() - current.computedAt() < CACHE_TTL_MS) {
            return current;
        }
        PageRepository.PageStats pageStats = pageRepository.getStats();
        long usedBytes = computeUsedBytes();
        long freeBytes = Files.getFileStore(Path.of(pagesDir)).getUsableSpace();
        CachedStats fresh = new CachedStats(pageStats, usedBytes, freeBytes, System.currentTimeMillis());
        cachedStats.set(fresh);
        return fresh;
    }

    private long computeUsedBytes() throws IOException {
        Path dir = Path.of(pagesDir);
        if (!Files.exists(dir)) return 0;
        AtomicLong total = new AtomicLong();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".html"))
                    .forEach(p -> {
                        try {
                            total.addAndGet(Files.size(p));
                        } catch (IOException ignored) {}
                    });
        }
        return total.get();
    }

    private static String humanReadable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        long kb = bytes / 1024;
        if (kb < 1024) return kb + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return "%.1f MB".formatted(mb);
        double gb = mb / 1024.0;
        return "%.1f GB".formatted(gb);
    }

    private record CachedStats(
            PageRepository.PageStats pageStats,
            long usedBytes,
            long freeBytes,
            long computedAt
    ) {}
}
