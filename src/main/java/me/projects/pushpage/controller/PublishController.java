package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.Page;
import me.projects.pushpage.model.PublishRequest;
import me.projects.pushpage.model.PublishResponse;
import me.projects.pushpage.service.PublishService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Pages", description = "Publish and manage HTML pages")
public class PublishController {

    private final PublishService publishService;

    public PublishController(PublishService publishService) {
        this.publishService = publishService;
    }

    @Operation(summary = "Publish an HTML page",
            description = "Saves the HTML as a static file and returns a public URL. " +
                    "If the content does not start with <!DOCTYPE>, it is automatically wrapped in a minimal HTML shell.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page published successfully",
                    content = @Content(schema = @Schema(implementation = PublishResponse.class))),
            @ApiResponse(responseCode = "500", description = "Failed to write file", content = @Content)
    })
    @PostMapping("/publish")
    public PublishResponse publish(@RequestBody PublishRequest request) {
        return publishService.publish(request);
    }

    @Operation(summary = "List all published pages",
            description = "Returns all pages ordered by publish date descending.")
    @ApiResponse(responseCode = "200", description = "List of pages",
            content = @Content(schema = @Schema(implementation = Page.class)))
    @GetMapping("/pages")
    public List<Page> listPages() {
        return publishService.listPages();
    }

    @Operation(summary = "Delete a published page",
            description = "Removes the HTML file and its metadata row.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Page deleted", content = @Content),
            @ApiResponse(responseCode = "404", description = "Page not found", content = @Content)
    })
    @DeleteMapping("/pages/{id}")
    public ResponseEntity<Void> deletePage(
            @Parameter(description = "8-character page ID", example = "a1b2c3d4")
            @PathVariable String id) {
        publishService.deletePage(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(hidden = true)
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String indexPage() {
        return buildIndexHtml(publishService.listPages());
    }

    @Operation(summary = "Health check")
    @ApiResponse(responseCode = "200", description = "Service is up")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // ── index page helpers ────────────────────────────────────────────────────

    private String buildIndexHtml(List<Page> pages) {
        var rows = new StringBuilder();
        for (Page page : pages) {
            rows.append("""
                    <tr>
                        <td><a href="%s">%s</a></td>
                        <td style="color:#666;font-size:.9em">%s</td>
                        <td><button onclick="del('%s')">Delete</button></td>
                    </tr>
                    """.formatted(page.url(), esc(page.title()), page.createdAt(), page.id()));
        }
        if (pages.isEmpty()) {
            rows.append("<tr><td colspan='3' style='text-align:center;color:#999;padding:24px'>No pages published yet.</td></tr>");
        }
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>postpage</title>
                    <style>
                        body{font-family:sans-serif;max-width:960px;margin:40px auto;padding:0 24px;line-height:1.6;color:#222}
                        h1{font-size:1.6rem;margin-bottom:4px}p{margin-top:0;color:#666}
                        table{width:100%%;border-collapse:collapse;margin-top:16px}
                        th,td{text-align:left;padding:10px 12px;border-bottom:1px solid #eee}
                        th{background:#f7f7f7;font-weight:600;font-size:.9em;text-transform:uppercase;letter-spacing:.04em}
                        a{color:#0066cc;text-decoration:none}a:hover{text-decoration:underline}
                        button{background:#e74c3c;color:#fff;border:none;padding:4px 12px;border-radius:4px;cursor:pointer;font-size:.85em}
                        button:hover{background:#c0392b}
                    </style>
                </head>
                <body>
                    <h1>postpage</h1>
                    <p>%d page%s published</p>
                    <table>
                        <thead><tr><th>Title</th><th>Published</th><th></th></tr></thead>
                        <tbody>%s</tbody>
                    </table>
                    <script>
                        function del(id){
                            if(!confirm('Delete this page?'))return;
                            fetch('/pages/'+id,{method:'DELETE'})
                                .then(r=>{if(r.ok)location.reload();else alert('Delete failed')});
                        }
                    </script>
                </body>
                </html>
                """.formatted(pages.size(), pages.size() == 1 ? "" : "s", rows);
    }

    private String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
