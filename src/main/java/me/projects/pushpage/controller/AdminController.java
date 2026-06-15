package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.CreateUserRequest;
import me.projects.pushpage.model.CreateUserResponse;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@Tag(name = "Admin", description = "User management — requires admin API key")
public class AdminController {

    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Create a user", description = "Creates a new user and returns their generated API key.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created",
                    content = @Content(schema = @Schema(implementation = CreateUserResponse.class))),
            @ApiResponse(responseCode = "400", description = "Username is blank", content = @Content),
            @ApiResponse(responseCode = "409", description = "Username already exists", content = @Content)
    })
    @PostMapping("/users")
    public ResponseEntity<CreateUserResponse> createUser(@RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(request));
    }

    @Operation(summary = "List all users", description = "Returns all users ordered by creation date.")
    @ApiResponse(responseCode = "200", description = "List of users",
            content = @Content(schema = @Schema(implementation = UserSummary.class)))
    @GetMapping("/users")
    public List<UserSummary> listUsers() {
        return userService.listUsers();
    }

    @Operation(summary = "Deactivate a user", description = "Marks the user as inactive. Their pages are retained.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User deactivated", content = @Content),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    @PatchMapping("/users/{id}/deactivate")
    public ResponseEntity<Void> deactivateUser(
            @Parameter(description = "8-character user ID") @PathVariable String id) {
        userService.deactivateUser(id);
        return ResponseEntity.noContent().build();
    }
}
