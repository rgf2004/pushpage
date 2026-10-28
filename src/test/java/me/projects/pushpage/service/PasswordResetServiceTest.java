package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.PasswordResetRepository;
import me.projects.pushpage.repository.PasswordResetRepository.RedeemedToken;
import me.projects.pushpage.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetRepository resetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserLifecycleHooks lifecycleHooks;

    private PasswordResetService service;

    private final User alice = new User("u1", "alice@example.com", "keyhash", "oldhash", Instant.now(), true, false);

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(resetRepository, userRepository, lifecycleHooks,
                "https://pushpage.test", 60);
    }

    @Test
    void issueToken_storesOnlyTheHashAndBuildsDashboardLink() {
        var issued = service.issueToken(alice, true);

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(resetRepository).replaceToken(eq("u1"), storedHash.capture(), any(), expiresAt.capture(), eq(true));

        assertThat(issued.rawToken()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(storedHash.getValue()).isEqualTo(PasswordResetService.hash(issued.rawToken()))
                .isNotEqualTo(issued.rawToken());
        assertThat(issued.link()).isEqualTo("https://pushpage.test/dashboard?reset_token=" + issued.rawToken());
        assertThat(expiresAt.getValue()).isAfter(Instant.now().plusSeconds(59 * 60));
        assertThat(issued.expiresAt()).isEqualTo(expiresAt.getValue());
    }

    @Test
    void issueToken_producesDifferentTokensEachTime() {
        assertThat(service.issueToken(alice, false).rawToken())
                .isNotEqualTo(service.issueToken(alice, false).rawToken());
    }

    @Test
    void resetPassword_setsNewHashedPasswordAndClearsRemainingTokens() {
        when(resetRepository.redeem(eq(PasswordResetService.hash("tok")), any()))
                .thenReturn(Optional.of(new RedeemedToken("u1", false)));
        when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

        service.resetPassword("tok", "new-password-1");

        ArgumentCaptor<String> newHash = ArgumentCaptor.forClass(String.class);
        verify(userRepository).updatePassword(eq("u1"), newHash.capture(), any());
        assertThat(new BCryptPasswordEncoder().matches("new-password-1", newHash.getValue())).isTrue();
        verify(resetRepository).deleteByUserId("u1");
        verify(lifecycleHooks, never()).afterEmailOwnershipProven(any());
    }

    @Test
    void resetPassword_withEmailDeliveredToken_provesEmailOwnership() {
        when(resetRepository.redeem(anyString(), any())).thenReturn(Optional.of(new RedeemedToken("u1", true)));
        when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

        service.resetPassword("tok", "new-password-1");

        verify(lifecycleHooks).afterEmailOwnershipProven(alice);
    }

    @Test
    void resetPassword_trimsWhitespaceAroundToken() {
        when(resetRepository.redeem(eq(PasswordResetService.hash("tok")), any()))
                .thenReturn(Optional.of(new RedeemedToken("u1", false)));
        when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

        service.resetPassword("  tok\n", "new-password-1");

        verify(userRepository).updatePassword(eq("u1"), anyString(), any());
    }

    @Test
    void resetPassword_unknownOrExpiredToken_isRejected() {
        when(resetRepository.redeem(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("nope", "new-password-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid or expired");
        verify(userRepository, never()).updatePassword(anyString(), anyString(), any());
    }

    @Test
    void resetPassword_blankOrNullToken_isRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.resetPassword("  ", "new-password-1"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.resetPassword(null, "new-password-1"))
                .isInstanceOf(ResponseStatusException.class);
        verify(resetRepository, never()).redeem(anyString(), any());
    }

    @Test
    void resetPassword_shortPassword_isRejectedBeforeRedeemingTheToken() {
        assertThatThrownBy(() -> service.resetPassword("tok", "short"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least 8");
        assertThatThrownBy(() -> service.resetPassword("tok", null))
                .isInstanceOf(ResponseStatusException.class);
        verify(resetRepository, never()).redeem(anyString(), any());
    }

    @Test
    void resetPassword_deactivatedUser_isRejected() {
        User inactive = new User("u1", "alice@example.com", "k", "h", Instant.now(), false, false);
        when(resetRepository.redeem(anyString(), any())).thenReturn(Optional.of(new RedeemedToken("u1", true)));
        when(userRepository.findById("u1")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.resetPassword("tok", "new-password-1"))
                .isInstanceOf(ResponseStatusException.class);
        verify(userRepository, never()).updatePassword(anyString(), anyString(), any());
        verify(lifecycleHooks, never()).afterEmailOwnershipProven(any());
    }

    @Test
    void issueTokenForAdmin_unknownUser_is404() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueTokenForAdmin("ghost"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void issueTokenForAdmin_deactivatedUser_isRejected() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(
                new User("u1", "a@b.co", "k", "h", Instant.now(), false, false)));

        assertThatThrownBy(() -> service.issueTokenForAdmin("u1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    void issueTokenForAdmin_marksTokenAsNotEmailDelivered() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

        service.issueTokenForAdmin("u1");

        verify(resetRepository).replaceToken(eq("u1"), anyString(), any(), any(), eq(false));
    }

    @Test
    void purgeExpiredTokens_deletesExpired() {
        service.purgeExpiredTokens();

        verify(resetRepository).deleteExpired(any());
    }
}
