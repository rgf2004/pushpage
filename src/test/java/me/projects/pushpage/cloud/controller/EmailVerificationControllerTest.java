package me.projects.pushpage.cloud.controller;

import jakarta.servlet.http.HttpServletRequest;
import me.projects.pushpage.cloud.model.ResendVerificationRequest;
import me.projects.pushpage.cloud.service.EmailVerificationService;
import me.projects.pushpage.cloud.service.ResendOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationControllerTest {

    @Mock
    private EmailVerificationService verificationService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private EmailVerificationController controller;

    @Test
    void resendVerification_whenAccepted_returns204AndPassesClientIp() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(verificationService.resendVerification(eq("alice@example.com"), eq("10.0.0.1")))
                .thenReturn(new ResendOutcome(false, 0));

        ResponseEntity<Void> response = controller.resendVerification(
                new ResendVerificationRequest("alice@example.com"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void resendVerification_whenRateLimited_returns429WithRetryAfter() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(verificationService.resendVerification(anyString(), anyString()))
                .thenReturn(new ResendOutcome(true, 42));

        ResponseEntity<Void> response = controller.resendVerification(
                new ResendVerificationRequest("alice@example.com"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("42");
    }

    @Test
    void resendVerification_usesXForwardedForHeaderWhenPresent() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(verificationService.resendVerification(anyString(), anyString()))
                .thenReturn(new ResendOutcome(false, 0));

        controller.resendVerification(new ResendVerificationRequest("alice@example.com"), request);

        verify(verificationService).resendVerification("alice@example.com", "203.0.113.5");
    }
}
