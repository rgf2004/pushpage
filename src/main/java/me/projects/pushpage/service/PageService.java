package me.projects.pushpage.service;

import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.Page;
import me.projects.pushpage.model.PublishRequest;
import me.projects.pushpage.model.PublishResponse;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.PageRepository;
import me.projects.pushpage.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import org.jsoup.Jsoup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class PageService {

    private static final Logger log = LoggerFactory.getLogger(PageService.class);

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.pages-dir}")
    private String pagesDir;

    @Value("${app.max-file-size}")
    private DataSize maxFileSize;

    @Value("${app.guest.expiration-minutes:30}")
    private int guestExpirationMinutes;

    private final PageRepository pageRepository;
    private final HealthService healthService;
    private final AuthContext authContext;
    private final QuotaPolicy quotaPolicy;
    private final RetentionPolicy retentionPolicy;
    private final UserRepository userRepository;

    public PageService(PageRepository pageRepository, HealthService healthService,
                       AuthContext authContext, QuotaPolicy quotaPolicy, RetentionPolicy retentionPolicy,
                       UserRepository userRepository) {
        this.userRepository = userRepository;
        this.pageRepository = pageRepository;
        this.healthService = healthService;
        this.authContext = authContext;
        this.quotaPolicy = quotaPolicy;
        this.retentionPolicy = retentionPolicy;
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
        String title = (request.title() != null && !request.title().isBlank())
                ? request.title() : extractTitle(request.html());
        return publishHtml(request.html(), title);
    }

    public PublishResponse publishFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File part is required");
        }
        String html;
        try {
            html = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
        String titleFromTag = extractTitle(html);
        String filenameTitle = titleFromFilename(file.getOriginalFilename());
        String title = !titleFromTag.equals("Untitled") ? titleFromTag
                : (filenameTitle != null ? filenameTitle : "Untitled");
        return publishHtml(html, title);
    }

    /** Replaces the HTML (and title) of an existing page, keeping its id and URL. JSON variant. */
    public PublishResponse update(String id, PublishRequest request) {
        if (request.html() == null || request.html().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "HTML content is required");
        }
        String title = (request.title() != null && !request.title().isBlank())
                ? request.title() : extractTitle(request.html());
        return updateHtml(id, request.html(), title);
    }

    /** Multipart variant of {@link #update(String, PublishRequest)}. */
    public PublishResponse updateFile(String id, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File part is required");
        }
        String html;
        try {
            html = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
        String titleFromTag = extractTitle(html);
        String filenameTitle = titleFromFilename(file.getOriginalFilename());
        String title = !titleFromTag.equals("Untitled") ? titleFromTag
                : (filenameTitle != null ? filenameTitle : "Untitled");
        return updateHtml(id, html, title);
    }

    private PublishResponse updateHtml(String id, String html, String title) {
        User currentUser = requireCurrentUser();
        // Unknown, deleted and expired-but-not-yet-cleaned pages are all 404.
        Page page = pageRepository.findById(id)
                .filter(p -> p.expiresAt() == null || p.expiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found: " + id));
        if (page.userId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Guest pages cannot be updated; publish a new page instead");
        }
        if (!currentUser.admin() && !currentUser.id().equals(page.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only update your own pages");
        }
        // No quota check: updates are limited by the per-minute rate limit only (they create no page,
        // so they never use up the daily publish quota either).
        long sizeBytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > maxFileSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Payload size %d bytes exceeds maximum allowed size of %d bytes".formatted(sizeBytes, maxFileSize.toBytes()));
        }
        String wrappedHtml = wrapIfNeeded(html, title);

        // The retention window restarts from now under the page owner's plan (an admin editing
        // someone else's page must not change it by their own plan).
        Instant expiresAt = currentUser.id().equals(page.userId())
                ? retentionPolicy.expiresAt(currentUser)
                : userRepository.findById(page.userId())
                        .map(retentionPolicy::expiresAt)
                        .orElseGet(() -> retentionPolicy.expiresAt(currentUser));

        // Order matters. 1) write a temp file (a dotfile: nginx never serves it), 2) rename it over the
        // page (atomic: readers see the old or the new content, never a partial file), 3) only then
        // update the row. A failure in 1 or 2 leaves the old file AND the old title/expiry untouched.
        // Interleavings with deletePage / CleanupService, which do "delete file, then soft-delete row":
        //  - they finish before our move: the page row is gone, so our move re-creates a file, but step 3
        //    matches 0 rows (updateContent requires deleted_at IS NULL), and we remove that file again and
        //    answer 404. Nothing stays served without a live row.
        //  - they run between our move and our updateContent: they delete the file we just wrote and
        //    soft-delete the row; updateContent then matches 0 rows and we delete the (already gone) file.
        //  - they run after our updateContent: the page is simply deleted after being updated.
        // In no ordering does a soft-deleted page keep a file on disk. (A reader can briefly see the new
        // content of a page that is being deleted at that same moment; that is harmless.)
        Path target = Path.of(pagesDir, id + ".html");
        Path temp = Path.of(pagesDir, "." + id + "." + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
        try {
            Files.writeString(temp, wrappedHtml);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new RuntimeException("Failed to write page file", e);
        }
        if (!pageRepository.updateContent(id, title, expiresAt)) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                log.warn("Could not remove file of page {} deleted during an update", id, e);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found: " + id);
        }
        healthService.invalidateCache();
        return new PublishResponse(baseUrl + "/" + id + ".html", id, expiresAt);
    }

    private PublishResponse publishHtml(String html, String title) {
        User currentUser = authContext.getCurrentUser();
        quotaPolicy.check(currentUser);
        long sizeBytes = html.getBytes(StandardCharsets.UTF_8).length;
        if (sizeBytes > maxFileSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Payload size %d bytes exceeds maximum allowed size of %d bytes".formatted(sizeBytes, maxFileSize.toBytes()));
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String wrappedHtml = wrapIfNeeded(html, title);

        try {
            Files.writeString(Path.of(pagesDir, id + ".html"), wrappedHtml);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write page file", e);
        }

        String userId = currentUser != null ? currentUser.id() : null;
        Instant expiresAt = expiresAt(currentUser);
        pageRepository.save(id, title, userId, expiresAt);
        healthService.invalidateCache();

        String url = baseUrl + "/" + id + ".html";
        return new PublishResponse(url, id, expiresAt);
    }

    public List<Page> listPages() {
        User currentUser = requireCurrentUser();
        return pageRepository.findAll(baseUrl, currentUser.id(), currentUser.admin());
    }

    public void deletePage(String id) {
        Page page = pageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found: " + id));

        User currentUser = requireCurrentUser();
        if (!currentUser.admin() && !currentUser.id().equals(page.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own pages");
        }

        try {
            Files.deleteIfExists(Path.of(pagesDir, id + ".html"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete page file", e);
        }

        pageRepository.softDeleteById(id);
        healthService.invalidateCache();
    }

    private Instant expiresAt(User user) {
        return user == null
                ? Instant.now().plus(guestExpirationMinutes, ChronoUnit.MINUTES)
                : retentionPolicy.expiresAt(user);
    }

    private User requireCurrentUser() {
        User user = authContext.getCurrentUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }

    private String extractTitle(String html) {
        String candidate = Jsoup.parse(html).title().strip();
        return candidate.isBlank() ? "Untitled" : candidate;
    }

    private String titleFromFilename(String originalFilename) {
        String base = StringUtils.stripFilenameExtension(originalFilename);
        return (base != null && !base.isBlank()) ? base : null;
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
