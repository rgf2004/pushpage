package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Hidden;
import me.projects.pushpage.repository.PageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Renders the branded viewer shell around a published page. nginx rewrites {@code /p/{id}}
 * to this endpoint. The shell holds only the title and id (never the page's HTML), and
 * embeds the sandboxed {@code /pages/{id}.html} in an iframe.
 */
@RestController
@Hidden
public class ViewerController {

    private final PageRepository pageRepository;
    private final String template;
    private final String serverUrl;

    public ViewerController(PageRepository pageRepository, @Value("${app.server-url}") String serverUrl) {
        this.pageRepository = pageRepository;
        this.serverUrl = serverUrl;
        try (var in = new ClassPathResource("viewer/viewer.html").getInputStream()) {
            this.template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping(value = "/viewer/{id:[a-zA-Z0-9]+}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> viewer(@PathVariable String id) {
        var page = pageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found"));
        String html = template
                .replace("{{TITLE}}", HtmlUtils.htmlEscape(page.title()))
                .replace("{{ID}}", id)
                .replace("{{BASE}}", HtmlUtils.htmlEscape(serverUrl));
        return ResponseEntity.ok().header("Cache-Control", "no-cache").body(html);
    }
}
