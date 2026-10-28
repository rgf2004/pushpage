package me.projects.pushpage.emailverification.service;

import me.projects.pushpage.emailverification.email.EmailService;
import me.projects.pushpage.emailverification.repository.EmailVerificationRepository;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import me.projects.pushpage.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    private EmailVerificationRepository verificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    // Real instance (not mocked) — tryConsume() takes capacity as an explicit
    // argument and doesn't need Spring-injected @Value fields, so this exercises
    // the actual bucket4j rate-limiting logic.
    private final RateLimitService rateLimitService = new RateLimitService();

    private EmailVerificationService service;

    private final User user = new User("id1", "alice@example.com", "pp_key", "hash", Instant.now(), true, false);

    private EmailVerificationService newService() {
        return newService(1, 5);
    }

    private EmailVerificationService newService(int verificationRateLimitPerMinute) {
        return newService(verificationRateLimitPerMinute, 5);
    }

    private EmailVerificationService newService(int verificationRateLimitPerMinute, int resendIpRateLimitPerMinute) {
        return newService(verificationRateLimitPerMinute, resendIpRateLimitPerMinute, 24);
    }

    private EmailVerificationService newService(int verificationRateLimitPerMinute, int resendIpRateLimitPerMinute, long tokenTtlHours) {
        return new EmailVerificationService(verificationRepository, userRepository, emailService,
                rateLimitService, "https://pushpage.link", verificationRateLimitPerMinute, resendIpRateLimitPerMinute, tokenTtlHours);
    }

    @Test
    void sendVerificationEmail_storesTokenAndSendsLinkContainingIt() {
        service = newService();

        service.sendVerificationEmail(user);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(verificationRepository).setVerificationToken(eq("id1"), tokenCaptor.capture(), any());
        String token = tokenCaptor.getValue();

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendVerificationEmail(eq("alice@example.com"), linkCaptor.capture());
        assertThat(linkCaptor.getValue())
                .isEqualTo("https://pushpage.link/api/auth/verify-email?token=" + token);
    }

    @Test
    void sendVerificationEmail_usesConfiguredTokenTtl() {
        service = newService(1, 5, 2);

        Instant before = Instant.now();
        service.sendVerificationEmail(user);

        ArgumentCaptor<Instant> expiryCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(verificationRepository).setVerificationToken(eq("id1"), anyString(), expiryCaptor.capture());
        assertThat(expiryCaptor.getValue()).isBetween(before.plusSeconds(2 * 3600 - 5), before.plusSeconds(2 * 3600 + 5));
    }

    @Test
    void sendVerificationEmail_whenCalledRepeatedlyForSameUser_isRateLimited() {
        service = newService(1);

        service.sendVerificationEmail(user);
        service.sendVerificationEmail(user);
        service.sendVerificationEmail(user);

        verify(emailService, times(1)).sendVerificationEmail(anyString(), anyString());
        verify(verificationRepository, times(1)).setVerificationToken(anyString(), anyString(), any());
    }

    @Test
    void sendVerificationEmail_rateLimitIsPerUser_doesNotAffectOtherUsers() {
        service = newService(1);
        User otherUser = new User("id2", "bob@example.com", "pp_key2", "hash", Instant.now(), true, false);

        service.sendVerificationEmail(user);
        service.sendVerificationEmail(otherUser);

        verify(emailService).sendVerificationEmail(eq("alice@example.com"), anyString());
        verify(emailService).sendVerificationEmail(eq("bob@example.com"), anyString());
    }

    @Test
    void sendVerificationEmail_whenEmailServiceThrows_doesNotPropagate() {
        service = newService();
        doThrow(new RuntimeException("smtp down")).when(emailService).sendVerificationEmail(anyString(), anyString());

        service.sendVerificationEmail(user);
    }

    @Test
    void verifyToken_withValidToken_marksVerifiedAndReturnsSuccess() {
        service = newService();
        when(verificationRepository.findUserIdByValidToken("tok")).thenReturn(Optional.of("id1"));

        VerifyResult result = service.verifyToken("tok");

        assertThat(result).isEqualTo(VerifyResult.SUCCESS);
        verify(verificationRepository).markVerified("id1");
    }

    @Test
    void verifyToken_withUnknownToken_returnsInvalidAndDoesNotMarkVerified() {
        service = newService();
        when(verificationRepository.findUserIdByValidToken("bad-tok")).thenReturn(Optional.empty());

        VerifyResult result = service.verifyToken("bad-tok");

        assertThat(result).isEqualTo(VerifyResult.INVALID_OR_EXPIRED);
        verify(verificationRepository, never()).markVerified(anyString());
    }

    @Test
    void resendVerification_forUnverifiedUser_resendsEmailAndReturnsAccepted() {
        service = newService();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(verificationRepository.isVerified("id1")).thenReturn(false);

        ResendOutcome outcome = service.resendVerification("alice@example.com", "10.0.0.1");

        assertThat(outcome.rateLimited()).isFalse();
        verify(emailService).sendVerificationEmail(eq("alice@example.com"), anyString());
    }

    @Test
    void resendVerification_forAlreadyVerifiedUser_doesNotResend() {
        service = newService();
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(verificationRepository.isVerified("id1")).thenReturn(true);

        service.resendVerification("alice@example.com", "10.0.0.1");

        verify(emailService, never()).sendVerificationEmail(anyString(), anyString());
    }

    @Test
    void resendVerification_forUnknownEmail_doesNothing() {
        service = newService();
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.resendVerification("nobody@example.com", "10.0.0.1");

        verifyNoInteractions(emailService);
    }

    @Test
    void resendVerification_forBlankEmail_stillConsumesIpLimitButDoesNothing() {
        service = newService();

        service.resendVerification(" ", "10.0.0.1");

        verifyNoInteractions(userRepository, emailService);
    }

    @Test
    void resendVerification_whenIpLimitExceeded_returnsRateLimitedAndSkipsLookup() {
        service = newService(1, 1);

        service.resendVerification("alice@example.com", "10.0.0.2");
        ResendOutcome second = service.resendVerification("alice@example.com", "10.0.0.2");

        assertThat(second.rateLimited()).isTrue();
        assertThat(second.retryAfterSeconds()).isGreaterThan(0);
        verify(userRepository, times(1)).findByEmail(anyString());
    }

    @Test
    void resendVerification_ipLimitIsPerIp_doesNotAffectOtherIps() {
        service = newService(5, 1);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        service.resendVerification("alice@example.com", "10.0.0.3");
        ResendOutcome fromOtherIp = service.resendVerification("alice@example.com", "10.0.0.4");

        assertThat(fromOtherIp.rateLimited()).isFalse();
    }

    @Test
    void markVerifiedManually_delegatesToRepository() {
        service = newService();

        service.markVerifiedManually("id1");

        verify(verificationRepository).markVerified("id1");
    }
}
