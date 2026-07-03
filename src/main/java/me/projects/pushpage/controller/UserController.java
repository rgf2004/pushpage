package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.config.AuthContext;
import me.projects.pushpage.model.MeResponse;
import me.projects.pushpage.model.RotateTokenResponse;
import me.projects.pushpage.model.User;
import me.projects.pushpage.service.QuotaPolicy;
import me.projects.pushpage.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "User", description = "Account and API token management")
public class UserController {

    private final UserService userService;
    private final AuthContext authContext;
    private final QuotaPolicy quotaPolicy;

    public UserController(UserService userService, AuthContext authContext, QuotaPolicy quotaPolicy) {
        this.userService = userService;
        this.authContext = authContext;
        this.quotaPolicy = quotaPolicy;
    }

    @Operation(summary = "Get current user profile",
            description = "Returns the authenticated user's email, role, and (in cloud deployments) plan and today's publish usage.")
    @ApiResponse(responseCode = "200", description = "User profile",
            content = @Content(schema = @Schema(implementation = MeResponse.class)))
    @GetMapping("/me")
    public MeResponse me() {
        User user = authContext.requireCurrentUser();
        long usage = quotaPolicy.dailyUsage(user.id());
        Integer limit = quotaPolicy.dailyLimit(user);
        String plan = quotaPolicy.planName(user);
        return new MeResponse(user.id(), user.email(), user.admin(), plan, usage, limit);
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
