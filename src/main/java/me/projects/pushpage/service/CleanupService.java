package me.projects.pushpage.service;

import me.projects.pushpage.model.Page;
import me.projects.pushpage.repository.PageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    @Value("${app.cleanup.retention-days:30}")
    private int retentionDays;

    @Value("${app.pages-dir}")
    private String pagesDir;

    private final PageRepository pageRepository;

    public CleanupService(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    @Scheduled(cron = "${app.cleanup.schedule:0 0 * * * *}")
    public void runCleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<Page> candidates = pageRepository.findExpired(cutoff);

        if (candidates.isEmpty()) {
            log.info("Cleanup: no pages older than {} days found", retentionDays);
            return;
        }

        int deleted = 0;
        int errors = 0;
        for (Page page : candidates) {
            try {
                Files.deleteIfExists(Path.of(pagesDir, page.id() + ".html"));
                pageRepository.softDeleteById(page.id());
                log.info("Cleanup: deleted page id={} title='{}' created_at={}", page.id(), page.title(), page.createdAt());
                deleted++;
            } catch (IOException e) {
                log.error("Cleanup: failed to delete file for page id={}", page.id(), e);
                errors++;
            }
        }

        log.info("Cleanup complete — deleted {} pages, {} errors", deleted, errors);
    }
}
