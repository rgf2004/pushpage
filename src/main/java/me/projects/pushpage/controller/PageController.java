package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.constants.AppHeaders;
import me.projects.pushpage.model.Page;
import me.projects.pushpage.model.PublishRequest;
import me.projects.pushpage.model.PublishResponse;
import me.projects.pushpage.service.PageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@Tag(name = "Pages", description = "Publish and manage HTML pages")
public class PageController {

    private final PageService pageService;

    public PageController(PageService pageService) {
        this.pageService = pageService;
    }

    @Operation(summary = "Publish an HTML page (JSON)",
            description = "Saves the HTML as a static file and returns a public URL. " +
                    "If the content does not start with <!DOCTYPE>, it is automatically wrapped in a minimal HTML shell.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page published successfully",
                    content = @Content(schema = @Schema(implementation = PublishResponse.class))),
            @ApiResponse(responseCode = "413", description = "Payload exceeds the configured size limit", content = @Content),
            @ApiResponse(responseCode = "500", description = "Failed to write file", content = @Content)
    })
    @PostMapping("/pages")
    public ResponseEntity<PublishResponse> publish(@RequestBody PublishRequest request) {
        return ResponseEntity.ok()
                .header(AppHeaders.X_MAX_FILE_SIZE, String.valueOf(pageService.getMaxFileSizeBytes()))
                .body(pageService.publish(request));
    }

    @Operation(summary = "Publish an HTML page (file upload)",
            description = "Accepts a multipart/form-data upload of an .html file. " +
                    "Title is extracted from the <title> tag, then the filename, then falls back to 'Untitled'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page published successfully",
                    content = @Content(schema = @Schema(implementation = PublishResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing or empty file part", content = @Content),
            @ApiResponse(responseCode = "413", description = "File exceeds the configured size limit", content = @Content),
            @ApiResponse(responseCode = "500", description = "Failed to write file", content = @Content)
    })
    @PostMapping(value = "/pages/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PublishResponse> publishFile(
            @Parameter(description = "HTML file to publish")
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok()
                .header(AppHeaders.X_MAX_FILE_SIZE, String.valueOf(pageService.getMaxFileSizeBytes()))
                .body(pageService.publishFile(file));
    }

    @Operation(summary = "List all published pages",
            description = "Returns all pages ordered by publish date descending.")
    @ApiResponse(responseCode = "200", description = "List of pages",
            content = @Content(schema = @Schema(implementation = Page.class)))
    @GetMapping("/pages")
    public List<Page> listPages() {
        return pageService.listPages();
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
        pageService.deletePage(id);
        return ResponseEntity.noContent().build();
    }

}
