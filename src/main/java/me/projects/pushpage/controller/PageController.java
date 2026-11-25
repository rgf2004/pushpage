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

    @Operation(summary = "Publish an HTML page (JSON)", operationId = "publishPageJson",
            description = "Saves the HTML as a static file and returns a public URL. " +
                    "If the content does not start with <!DOCTYPE>, it is automatically wrapped in a minimal HTML shell.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page published successfully",
                    content = @Content(schema = @Schema(implementation = PublishResponse.class))),
            @ApiResponse(responseCode = "413", description = "Payload exceeds the configured size limit", content = @Content),
            @ApiResponse(responseCode = "500", description = "Failed to write file", content = @Content)
    })
    @PostMapping(value = "/pages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublishResponse> publish(@RequestBody PublishRequest request) {
        return ResponseEntity.ok()
                .header(AppHeaders.X_MAX_FILE_SIZE, String.valueOf(pageService.getMaxFileSizeBytes()))
                .body(pageService.publish(request));
    }

    @Operation(summary = "Publish an HTML page (file upload)", operationId = "publishPageFile",
            description = "Accepts a multipart/form-data upload of an .html file. " +
                    "Title is extracted from the <title> tag, then the filename, then falls back to 'Untitled'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page published successfully",
                    content = @Content(schema = @Schema(implementation = PublishResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing or empty file part", content = @Content),
            @ApiResponse(responseCode = "413", description = "File exceeds the configured size limit", content = @Content),
            @ApiResponse(responseCode = "500", description = "Failed to write file", content = @Content)
    })
    @PostMapping(value = "/pages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PublishResponse> publishFile(
            @Parameter(description = "HTML file to publish")
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok()
                .header(AppHeaders.X_MAX_FILE_SIZE, String.valueOf(pageService.getMaxFileSizeBytes()))
                .body(pageService.publishFile(file));
    }

    @Operation(summary = "Update a published page (JSON)", operationId = "updatePageJson",
            description = "Replaces the HTML (and title) of a page you own, keeping its id and URL. " +
                    "Expiry restarts from now under the owner's retention. Counts against the publish rate limit " +
                    "(not the daily quota). Guest pages cannot be updated.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page updated",
                    content = @Content(schema = @Schema(implementation = PublishResponse.class))),
            @ApiResponse(responseCode = "400", description = "Missing HTML", content = @Content),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not your page, or a guest page", content = @Content),
            @ApiResponse(responseCode = "404", description = "Page not found (or deleted/expired)", content = @Content),
            @ApiResponse(responseCode = "413", description = "Payload exceeds the configured size limit", content = @Content),
            @ApiResponse(responseCode = "429", description = "Rate limit reached", content = @Content)
    })
    @PutMapping(value = "/pages/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PublishResponse> update(
            @Parameter(description = "8-character page ID", example = "a1b2c3d4") @PathVariable String id,
            @RequestBody PublishRequest request) {
        return ResponseEntity.ok()
                .header(AppHeaders.X_MAX_FILE_SIZE, String.valueOf(pageService.getMaxFileSizeBytes()))
                .body(pageService.update(id, request));
    }

    @Operation(summary = "Update a published page (file upload)", operationId = "updatePageFile",
            description = "Same as the JSON variant, with a multipart/form-data .html file in the 'file' part.")
    @PutMapping(value = "/pages/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PublishResponse> updateFile(
            @Parameter(description = "8-character page ID", example = "a1b2c3d4") @PathVariable String id,
            @Parameter(description = "HTML file with the new content") @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok()
                .header(AppHeaders.X_MAX_FILE_SIZE, String.valueOf(pageService.getMaxFileSizeBytes()))
                .body(pageService.updateFile(id, file));
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
