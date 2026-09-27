package me.projects.pushpage.cloud.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import me.projects.pushpage.cloud.model.ResendVerificationRequest;
import me.projects.pushpage.cloud.service.EmailVerificationService;
import me.projects.pushpage.cloud.service.ResendOutcome;
import me.projects.pushpage.cloud.service.VerifyResult;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/auth")
@Profile("cloud")
@Tag(name = "Auth", description = "Email verification endpoints (cloud only)")
public class EmailVerificationController {

    private final EmailVerificationService verificationService;

    public EmailVerificationController(EmailVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Operation(summary = "Verify email", description = "Consumes a sign-up verification token and redirects to the dashboard.")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "Redirects to /dashboard with a verified/verify_error query flag", content = @Content)
    })
    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        VerifyResult result = verificationService.verifyToken(token);
        String redirect = result == VerifyResult.SUCCESS
                ? "/dashboard?verified=true"
                : "/dashboard?verify_error=invalid";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
    }

    @Operation(summary = "Resend verification email",
            description = "Re-sends the confirmation email if the address exists and is not yet verified. Always returns 204 to avoid leaking account existence. Rate-limited per IP.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Request accepted", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests from this IP", content = @Content)
    })
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@RequestBody ResendVerificationRequest request,
                                                     HttpServletRequest httpRequest) {
        ResendOutcome outcome =
                verificationService.resendVerification(request.email(), resolveClientIp(httpRequest));

        if (outcome.rateLimited()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(outcome.retryAfterSeconds()))
                    .build();
        }
        return ResponseEntity.noContent().build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
