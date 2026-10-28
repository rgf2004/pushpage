package me.projects.pushpage.email.controller;

import jakarta.servlet.http.HttpServletRequest;
import me.projects.pushpage.email.model.ForgotPasswordRequest;
import me.projects.pushpage.email.service.PasswordResetEmailService;
import me.projects.pushpage.email.service.ResendOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForgotPasswordControllerTest {

    @Mock
    private PasswordResetEmailService service;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private ForgotPasswordController controller;

    @Test
    void accepted_returns202() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(service.requestReset("alice@example.com", "10.0.0.1")).thenReturn(ResendOutcome.accepted());

        ResponseEntity<Void> response = controller.forgotPassword(new ForgotPasswordRequest("alice@example.com"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void rateLimited_returns429WithRetryAfter() {
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");
        when(service.requestReset("alice@example.com", "10.0.0.2")).thenReturn(ResendOutcome.rateLimited(17));

        ResponseEntity<Void> response = controller.forgotPassword(new ForgotPasswordRequest("alice@example.com"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("17");
    }

    @Test
    void usesFirstXForwardedForEntryAsClientIp() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");
        when(service.requestReset("alice@example.com", "203.0.113.5")).thenReturn(ResendOutcome.accepted());

        controller.forgotPassword(new ForgotPasswordRequest("alice@example.com"), request);

        verify(service).requestReset("alice@example.com", "203.0.113.5");
    }
}
