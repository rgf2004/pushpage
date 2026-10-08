package me.projects.pushpage.emailverification.controller;

import me.projects.pushpage.emailverification.service.EmailVerificationService;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationAdminControllerTest {

    static final User USER = new User("u1", "u@example.com", "k", "h", Instant.now(), true, false);

    @Mock EmailVerificationService verificationService;
    @Mock UserRepository userRepository;

    @InjectMocks
    EmailVerificationAdminController controller;

    // ── verifyEmailManually ──────────────────────────────────────────────────

    @Test
    void verifyEmailManually_whenUserExists_returns204() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(USER));

        var response = controller.verifyEmailManually("u1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(verificationService).markVerifiedManually("u1");
    }

    @Test
    void verifyEmailManually_whenUserNotFound_throws404() {
        when(userRepository.findById("u1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.verifyEmailManually("u1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(verificationService);
    }
}
