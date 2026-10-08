package me.projects.pushpage.cloud.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.projects.pushpage.cloud.config.ConditionalOnEmailVerificationEnabled;
import me.projects.pushpage.cloud.service.EmailVerificationService;
import me.projects.pushpage.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
@ConditionalOnEmailVerificationEnabled
@Tag(name = "Admin", description = "Email verification admin actions (EMAIL_VERIFICATION_ENABLED)")
public class EmailVerificationAdminController {

    private final EmailVerificationService verificationService;
    private final UserRepository userRepository;

    public EmailVerificationAdminController(EmailVerificationService verificationService,
                                            UserRepository userRepository) {
        this.verificationService = verificationService;
        this.userRepository = userRepository;
    }

    @Operation(summary = "Manually verify a user's email", description = "Marks the user's email as verified without requiring the confirmation link.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Email marked verified", content = @Content),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    })
    @PatchMapping("/users/{id}/verify-email")
    public ResponseEntity<Void> verifyEmailManually(
            @Parameter(description = "8-character user ID") @PathVariable String id) {
        if (userRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
        }
        verificationService.markVerifiedManually(id);
        return ResponseEntity.noContent().build();
    }
}
