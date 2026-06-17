package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.service.PublishService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "User", description = "Authenticated user info")
public class UserController {

    private final PublishService publishService;

    public UserController(PublishService publishService) {
        this.publishService = publishService;
    }

    @Operation(summary = "Get current authenticated user")
    @ApiResponse(responseCode = "200", description = "Current user",
            content = @Content(schema = @Schema(implementation = UserSummary.class)))
    @GetMapping("/me")
    public UserSummary me() {
        return publishService.getCurrentUser();
    }

}
