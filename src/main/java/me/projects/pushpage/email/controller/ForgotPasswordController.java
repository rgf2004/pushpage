package me.projects.pushpage.email.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import me.projects.pushpage.email.ConditionalOnEmailEnabled;
import me.projects.pushpage.email.model.ForgotPasswordRequest;
import me.projects.pushpage.email.service.PasswordResetEmailService;
import me.projects.pushpage.email.service.ResendOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@ConditionalOnEmailEnabled
@Tag(name = "Auth", description = "Forgot-password endpoint (EMAIL_ENABLED)")
public class ForgotPasswordController {

    private final PasswordResetEmailService passwordResetEmailService;

    public ForgotPasswordController(PasswordResetEmailService passwordResetEmailService) {
        this.passwordResetEmailService = passwordResetEmailService;
    }

    @Operation(summary = "Forgot password",
            description = "Emails a password reset link if the address belongs to an active account. Always returns 202 to avoid leaking which addresses are registered. Rate-limited per IP and per account.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Request accepted", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests from this IP", content = @Content)
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest httpRequest) {
        ResendOutcome outcome = passwordResetEmailService.requestReset(request.email(), resolveClientIp(httpRequest));
        if (outcome.rateLimited()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(outcome.retryAfterSeconds()))
                    .build();
        }
        return ResponseEntity.accepted().build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
