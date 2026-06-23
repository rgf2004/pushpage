package me.projects.pushpage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.model.LoginRequest;
import me.projects.pushpage.model.LoginResponse;
import me.projects.pushpage.model.SignUpRequest;
import me.projects.pushpage.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth", description = "Public sign-up and login endpoints")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Sign up", description = "Create a new account. No authentication required.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created", content = @Content),
            @ApiResponse(responseCode = "400", description = "Invalid email or weak password", content = @Content),
            @ApiResponse(responseCode = "409", description = "Email already registered", content = @Content)
    })
    @PostMapping("/signup")
    public ResponseEntity<Void> signUp(@RequestBody SignUpRequest request) {
        userService.signUp(request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "Log in", description = "Authenticate with email/username and password. Returns a short-lived JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @Content)
    })
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return userService.login(request);
    }
}
