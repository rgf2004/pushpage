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
            @ApiResponse(responseCode = "413", description = "Payload exceeds the configured size limit", content = @Content),
            @ApiResponse(responseCode = "500", description = "Failed to write file", content = @Content)
    })
    @PostMapping("/publish")
    public ResponseEntity<PublishResponse> publish(@RequestBody PublishRequest request) {
        PublishResponse response = publishService.publish(request);
        return ResponseEntity.ok()
                .header("X-Max-File-Size", String.valueOf(publishService.getMaxFileSizeBytes()))
                .body(response);
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

    @Operation(summary = "Health check")
    @ApiResponse(responseCode = "200", description = "Service is up")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

}
