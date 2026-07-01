package me.projects.pushpage.service;

import me.projects.pushpage.model.LoginRequest;
import me.projects.pushpage.model.LoginResponse;
import me.projects.pushpage.model.SignUpRequest;
import me.projects.pushpage.model.User;
import me.projects.pushpage.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserLifecycleHooks lifecycleHooks;

    @InjectMocks
    private UserService userService;

    // ── signUp ───────────────────────────────────────────────────────────────

    @Test
    void signUp_withValidRequest_savesUser() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.empty());

        userService.signUp(new SignUpRequest("alice@example.com", "securepass"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.email()).isEqualTo("alice@example.com");
        assertThat(saved.passwordHash()).isNotBlank();
        assertThat(saved.active()).isTrue();
        assertThat(saved.admin()).isFalse();
    }

    @Test
    void signUp_withInvalidEmail_throwsBadRequest() {
        assertThatThrownBy(() -> userService.signUp(new SignUpRequest("not-an-email", "securepass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).save(any());
    }

    @Test
    void signUp_withNullEmail_throwsBadRequest() {
        assertThatThrownBy(() -> userService.signUp(new SignUpRequest(null, "securepass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void signUp_withShortPassword_throwsBadRequest() {
        assertThatThrownBy(() -> userService.signUp(new SignUpRequest("alice@example.com", "short")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).save(any());
    }

    @Test
    void signUp_withDuplicateEmail_throwsConflict() {
        User existing = new User("id1", "alice@example.com", "pp_key", "hash", Instant.now(), true, false);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> userService.signUp(new SignUpRequest("alice@example.com", "securepass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(userRepository, never()).save(any());
    }

    @Test
    void signUp_passwordIsStoredAsBcryptHash() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        userService.signUp(new SignUpRequest("bob@example.com", "mypassword"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(BCRYPT.matches("mypassword", captor.getValue().passwordHash())).isTrue();
    }

    @Test
    void signUp_withValidRequest_invokesAfterSignUpHook() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        userService.signUp(new SignUpRequest("carol@example.com", "securepass"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(lifecycleHooks).afterSignUp(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("carol@example.com");
    }

    @Test
    void signUp_withInvalidEmail_doesNotInvokeAfterSignUpHook() {
        assertThatThrownBy(() -> userService.signUp(new SignUpRequest("not-an-email", "securepass")))
                .isInstanceOf(ResponseStatusException.class);
        verify(lifecycleHooks, never()).afterSignUp(any());
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    void login_withValidCredentials_returnsJwt() {
        String hash = BCRYPT.encode("correctpass");
        User user = new User("id1", "alice@example.com", "pp_key", hash, Instant.now(), true, false);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        LoginResponse response = userService.login(new LoginRequest("alice@example.com", "correctpass"));

        assertThat(response.jwt()).isEqualTo("jwt-token");
        verify(lifecycleHooks).beforeLogin(user);
    }

    @Test
    void login_whenBeforeLoginHookThrows_propagatesAndSkipsJwtGeneration() {
        String hash = BCRYPT.encode("correctpass");
        User user = new User("id1", "alice@example.com", "pp_key", hash, Instant.now(), true, false);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Please verify your email"))
                .when(lifecycleHooks).beforeLogin(user);

        assertThatThrownBy(() -> userService.login(new LoginRequest("alice@example.com", "correctpass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void login_withWrongPassword_throwsUnauthorized() {
        String hash = BCRYPT.encode("correctpass");
        User user = new User("id1", "alice@example.com", "pp_key", hash, Instant.now(), true, false);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login(new LoginRequest("alice@example.com", "wrongpass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void login_withUnknownEmail_throwsUnauthorized() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(new LoginRequest("nobody@example.com", "pass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void login_withInactiveUser_throwsUnauthorizedWithDeactivatedMessage() {
        String hash = BCRYPT.encode("pass");
        User user = new User("id1", "alice@example.com", "pp_key", hash, Instant.now(), false, false);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login(new LoginRequest("alice@example.com", "pass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("Account is deactivated");
                });
    }

    @Test
    void login_withUserHavingNoPasswordHash_throwsUnauthorized() {
        User user = new User("id1", "legacy@example.com", "pp_key", null, Instant.now(), true, false);
        when(userRepository.findByEmail("legacy@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login(new LoginRequest("legacy@example.com", "anypass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void login_withBlankEmail_throwsBadRequest() {
        assertThatThrownBy(() -> userService.login(new LoginRequest("", "pass")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
