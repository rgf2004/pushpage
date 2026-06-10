package me.projects.pushpage.service;

import me.projects.pushpage.model.Page;
import me.projects.pushpage.model.PublishRequest;
import me.projects.pushpage.model.PublishResponse;
import me.projects.pushpage.repository.PageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.pages-dir}")
    private String pagesDir;

    @Value("${app.max-file-size}")
    private DataSize maxFileSize;

    private final PageRepository pageRepository;
    private final HealthService healthService;

    public PublishService(PageRepository pageRepository, HealthService healthService) {
        this.pageRepository = pageRepository;
        this.healthService = healthService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Path.of(pagesDir));
        log.info("Max file size: {} bytes", maxFileSize.toBytes());
    }

    public long getMaxFileSizeBytes() {
        return maxFileSize.toBytes();
    }

    public PublishResponse publish(PublishRequest request) {
        if (request.html() == null || request.html().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTML content is required");
        }
        long sizeBytes = request.html().getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > maxFileSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Payload size %d bytes exceeds maximum allowed size of %d bytes".formatted(sizeBytes, maxFileSize.toBytes()));
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String title = (request.title() != null && !request.title().isBlank())
                ? request.title() : "Untitled";
        String html = wrapIfNeeded(request.html(), title);

        try {
            Files.writeString(Path.of(pagesDir, id + ".html"), html);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write page file", e);
        }

        pageRepository.save(id, title);
        healthService.invalidateCache();

        String url = baseUrl + "/" + id + ".html";
        return new PublishResponse(url, id);
    }

    public List<Page> listPages() {
        return pageRepository.findAll(baseUrl);
    }

    public void deletePage(String id) {
        if (!pageRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found: " + id);
        }
        try {
            Files.deleteIfExists(Path.of(pagesDir, id + ".html"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete page file", e);
        }
        pageRepository.deleteById(id);
        healthService.invalidateCache();
    }

    private String wrapIfNeeded(String html, String title) {
        if (html.trim().toLowerCase().startsWith("<!doctype")) {
            return html;
        }
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>%s</title>
                    <style>body{font-family:sans-serif;max-width:900px;margin:40px auto;padding:0 20px;line-height:1.6}</style>
                </head>
                <body>%s</body>
                </html>
                """.formatted(title, html);
    }
}
