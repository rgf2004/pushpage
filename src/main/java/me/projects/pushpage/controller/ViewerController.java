package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Hidden;
import me.projects.pushpage.repository.PageRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders the branded viewer shell around a published page. nginx rewrites {@code /p/{id}}
 * to this endpoint. The shell embeds the sandboxed {@code /pages/{id}.html} in an iframe and
 * never contains the page's HTML. Link-preview crawlers only read the shell, so the page's own
 * Open Graph / Twitter / description meta tags are copied into the shell's {@code <head>}
 * (rebuilt tag by tag with only {@code property}/{@code name}/{@code content}), with pushpage
 * defaults for anything the page does not set.
 */
@RestController
@Hidden
public class ViewerController {

    private static final int MAX_META_TAGS = 30;
    private static final int MAX_META_CONTENT_LENGTH = 1000;

    private final PageRepository pageRepository;
    private final String pagesDir;
    private final String serverUrl;
    private final String template;

    public ViewerController(PageRepository pageRepository,
                            @Value("${app.pages-dir}") String pagesDir,
                            @Value("${app.server-url}") String serverUrl) {
        this.pageRepository = pageRepository;
        this.pagesDir = pagesDir;
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
        String title = HtmlUtils.htmlEscape(page.title());
        String html = template
                .replace("{{META}}", metaTags(id, page.title()))
                .replace("{{TITLE}}", title)
                .replace("{{ID}}", id);
        return ResponseEntity.ok().header("Cache-Control", "no-cache").body(html);
    }

    private String metaTags(String id, String title) {
        Map<String, Element> tags = new LinkedHashMap<>();
        readPageMeta(id, tags);
        addDefault(tags, "property", "og:type", "website");
        addDefault(tags, "property", "og:title", title);
        addDefault(tags, "property", "og:description", "Published with pushpage: HTML pages from AI agents, shared as a link.");
        addDefault(tags, "property", "og:image", serverUrl + "/static/og-image.png");
        StringBuilder out = new StringBuilder();
        tags.values().forEach(tag -> out.append(tag.outerHtml()).append("\n    "));
        return out.toString().stripTrailing();
    }

    private void readPageMeta(String id, Map<String, Element> tags) {
        Path file = Path.of(pagesDir, id + ".html");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (Element meta : Jsoup.parse(Files.readString(file)).select("meta")) {
                if (tags.size() >= MAX_META_TAGS) {
                    break;
                }
                String content = meta.attr("content");
                if (content.isBlank() || content.length() > MAX_META_CONTENT_LENGTH) {
                    continue;
                }
                String property = meta.attr("property");
                String name = meta.attr("name");
                if (property.startsWith("og:")) {
                    tags.putIfAbsent("property:" + property, tag("property", property, content));
                } else if (name.startsWith("twitter:") || name.equals("description")) {
                    tags.putIfAbsent("name:" + name, tag("name", name, content));
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable or malformed page: fall back to the defaults
        }
    }

    private void addDefault(Map<String, Element> tags, String attr, String key, String content) {
        tags.putIfAbsent(attr + ":" + key, tag(attr, key, content));
    }

    private Element tag(String attr, String key, String content) {
        return new Element("meta").attr(attr, key).attr("content", content);
    }
}
