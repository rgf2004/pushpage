package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.RotateTokenResponse;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.service.PageService;
import me.projects.pushpage.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "User", description = "Authenticated user info and API token management")
public class UserController {

    private final PageService pageService;
    private final UserService userService;
    private final AuthContext authContext;

    public UserController(PageService pageService, UserService userService, AuthContext authContext) {
        this.pageService = pageService;
        this.userService = userService;
        this.authContext = authContext;
    }

    @Operation(summary = "Get current authenticated user")
    @ApiResponse(responseCode = "200", description = "Current user",
            content = @Content(schema = @Schema(implementation = UserSummary.class)))
    @GetMapping("/me")
    public UserSummary me() {
        return pageService.getCurrentUser();
    }

    @Operation(summary = "Generate or rotate API key",
            description = "Issues a new API key for the authenticated user. The previous key is immediately invalidated. The raw key is returned once — store it securely.")
    @ApiResponse(responseCode = "200", description = "New API key",
            content = @Content(schema = @Schema(implementation = RotateTokenResponse.class)))
    @PostMapping("/me/tokens")
    public RotateTokenResponse rotateApiToken() {
        return userService.rotateApiToken(authContext.getCurrentUser());
    }
}
