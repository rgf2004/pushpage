package me.projects.pushpage.email.service;

import me.projects.pushpage.email.sender.EmailService;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.PasswordResetService;
import me.projects.pushpage.service.PasswordResetService.IssuedToken;
import me.projects.pushpage.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetEmailServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private EmailService emailService;

    // Real instance: the limiter is part of the behaviour under test.
    private final RateLimitService rateLimitService = new RateLimitService();

    private final User alice = new User("u1", "alice@example.com", "k", "h", Instant.now(), true, false);

    /** Runs the send inline so tests can assert on it; production sends on a separate thread. */
    private final Executor inline = Runnable::run;

    private PasswordResetEmailService service;

    @BeforeEach
    void setUp() {
        service = new PasswordResetEmailService(userRepository, passwordResetService, emailService,
                rateLimitService, inline, 1, 5, 60);
    }

    private void aliceHasToken() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(passwordResetService.issueToken(alice, true))
                .thenReturn(new IssuedToken("raw", "https://pushpage.test/dashboard?reset_token=raw", Instant.now()));
    }

    @Test
    void knownActiveAccount_getsEmailWithLinkAndTtl() {
        aliceHasToken();

        ResendOutcome outcome = service.requestReset("alice@example.com", "10.0.0.1");

        assertThat(outcome.rateLimited()).isFalse();
        verify(emailService).sendPasswordResetEmail("alice@example.com",
                "https://pushpage.test/dashboard?reset_token=raw", 60);
    }

    @Test
    void emailLookupIsTrimmed() {
        aliceHasToken();

        service.requestReset("  alice@example.com ", "10.0.0.1");

        verify(emailService).sendPasswordResetEmail(anyString(), anyString(), anyLong());
    }

    @Test
    void unknownAddress_looksIdenticalAndSendsNothing() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        ResendOutcome outcome = service.requestReset("ghost@example.com", "10.0.0.1");

        assertThat(outcome.rateLimited()).isFalse();
        verifyNoInteractions(passwordResetService, emailService);
    }

    @Test
    void deactivatedAccount_looksIdenticalAndSendsNothing() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(
                new User("u1", "alice@example.com", "k", "h", Instant.now(), false, false)));

        ResendOutcome outcome = service.requestReset("alice@example.com", "10.0.0.1");

        assertThat(outcome.rateLimited()).isFalse();
        verifyNoInteractions(passwordResetService, emailService);
    }

    @Test
    void blankOrNullEmail_isAcceptedAndIgnored() {
        assertThat(service.requestReset(null, "10.0.0.1").rateLimited()).isFalse();
        assertThat(service.requestReset("  ", "10.0.0.1").rateLimited()).isFalse();
        verifyNoInteractions(userRepository, emailService);
    }

    @Test
    void perAccountLimit_silentlySkipsSecondEmailWithinTheMinute() {
        aliceHasToken();

        service.requestReset("alice@example.com", "10.0.0.1");
        ResendOutcome second = service.requestReset("alice@example.com", "10.0.0.2");

        assertThat(second.rateLimited()).isFalse();
        verify(emailService, org.mockito.Mockito.times(1)).sendPasswordResetEmail(anyString(), anyString(), anyLong());
    }

    @Test
    void perIpLimit_returnsRateLimitedWithRetryAfter() {
        service = new PasswordResetEmailService(userRepository, passwordResetService, emailService,
                rateLimitService, inline, 100, 2, 60);

        service.requestReset("a@x.co", "10.0.0.9");
        service.requestReset("b@x.co", "10.0.0.9");
        ResendOutcome third = service.requestReset("c@x.co", "10.0.0.9");

        assertThat(third.rateLimited()).isTrue();
        assertThat(third.retryAfterSeconds()).isPositive();
        // a different IP is unaffected
        assertThat(service.requestReset("d@x.co", "10.0.0.10").rateLimited()).isFalse();
    }

    @Test
    void smtpFailure_isSwallowedSoResponseStaysUniform() {
        aliceHasToken();
        doThrow(new IllegalStateException("smtp down"))
                .when(emailService).sendPasswordResetEmail(anyString(), anyString(), anyLong());

        ResendOutcome outcome = service.requestReset("alice@example.com", "10.0.0.1");

        assertThat(outcome.rateLimited()).isFalse();
    }

    @Test
    void tokenIsOnlyIssuedAsEmailDelivered() {
        aliceHasToken();

        service.requestReset("alice@example.com", "10.0.0.1");

        verify(passwordResetService).issueToken(any(), org.mockito.ArgumentMatchers.eq(true));
        verify(passwordResetService, never()).issueToken(any(), org.mockito.ArgumentMatchers.eq(false));
    }
}
