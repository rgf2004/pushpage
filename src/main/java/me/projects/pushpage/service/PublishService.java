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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class PublishService {

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.pages-dir}")
    private String pagesDir;

    private final PageRepository pageRepository;

    public PublishService(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Path.of(pagesDir));
    }

    public PublishResponse publish(PublishRequest request) {
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
