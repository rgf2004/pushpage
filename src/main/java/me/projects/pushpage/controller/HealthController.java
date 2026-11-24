package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.HealthResponse;
import me.projects.pushpage.model.PublicHealthResponse;
import me.projects.pushpage.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health", description = "Service health (public) and runtime statistics (admin)")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @Operation(summary = "Health check",
            description = "Public. Returns only the service status: HTTP 200 with {\"status\":\"UP\"} when healthy, "
                    + "503 with {\"status\":\"DOWN\"} when a critical subsystem (database or storage) is unavailable. "
                    + "Runtime statistics are available to admins at GET /api/admin/health.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service is healthy",
                    content = @Content(schema = @Schema(implementation = PublicHealthResponse.class))),
            @ApiResponse(responseCode = "503", description = "Service is degraded",
                    content = @Content(schema = @Schema(implementation = PublicHealthResponse.class)))
    })
    @GetMapping("/health")
    public ResponseEntity<PublicHealthResponse> health() {
        String status = healthService.getHealth().status();
        HttpStatus http = "UP".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(new PublicHealthResponse(status));
    }

    @Operation(summary = "Detailed health and runtime statistics (admin)",
            description = "Admin only. Returns version, uptime, live and deleted page counts, oldest and newest page "
                    + "timestamps and storage usage. Stats are cached for 30 seconds. HTTP 200 when healthy, 503 otherwise.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service is healthy",
                    content = @Content(schema = @Schema(implementation = HealthResponse.class))),
            @ApiResponse(responseCode = "503", description = "Service is degraded",
                    content = @Content(schema = @Schema(implementation = HealthResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid credentials", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not an admin", content = @Content)
    })
    @GetMapping("/admin/health")
    public ResponseEntity<HealthResponse> adminHealth() {
        HealthResponse response = healthService.getHealth();
        HttpStatus http = "UP".equals(response.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(response);
    }
}
