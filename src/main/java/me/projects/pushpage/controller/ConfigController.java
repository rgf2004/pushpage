package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.ClientConfigResponse;
import me.projects.pushpage.service.ClientConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Config", description = "Public server configuration for frontends")
public class ConfigController {

    private final ClientConfigService clientConfigService;

    public ConfigController(ClientConfigService clientConfigService) {
        this.clientConfigService = clientConfigService;
    }

    @Operation(summary = "Public client configuration",
            description = "Returns non-sensitive settings (such as enabled feature flags) that the dashboard uses to adapt its UI. No authentication required.")
    @ApiResponse(responseCode = "200", description = "Current configuration",
            content = @Content(schema = @Schema(implementation = ClientConfigResponse.class)))
    @GetMapping("/config")
    public ClientConfigResponse config() {
        return clientConfigService.getConfig();
    }
}
