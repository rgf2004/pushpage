package me.projects.pushpage.cloud.service;

import me.projects.pushpage.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationHooksTest {

    @Mock
    private EmailVerificationService verificationService;

    @InjectMocks
    private EmailVerificationHooks hooks;

    private final User user = new User("id1", "alice@example.com", "pp_key", "hash", Instant.now(), true, false);

    @Test
    void afterSignUp_sendsVerificationEmail() {
        hooks.afterSignUp(user);

        verify(verificationService).sendVerificationEmail(user);
    }

    @Test
    void beforeLogin_whenVerified_doesNotThrow() {
        when(verificationService.isVerified("id1")).thenReturn(true);

        hooks.beforeLogin(user);
    }

    @Test
    void beforeLogin_whenNotVerified_throwsForbidden() {
        when(verificationService.isVerified("id1")).thenReturn(false);

        assertThatThrownBy(() -> hooks.beforeLogin(user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
