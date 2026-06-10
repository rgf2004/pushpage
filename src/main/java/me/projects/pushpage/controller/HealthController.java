package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.HealthResponse;
import me.projects.pushpage.service.HealthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health", description = "Service health and runtime statistics")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @Operation(summary = "Health check",
            description = "Returns runtime statistics. HTTP 200 when healthy, 503 when a critical subsystem is unavailable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service is healthy",
                    content = @Content(schema = @Schema(implementation = HealthResponse.class))),
            @ApiResponse(responseCode = "503", description = "Service is degraded", content = @Content)
    })
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse response = healthService.getHealth();
        HttpStatus status = "UP".equals(response.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }
}
