package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRecoveryRunnerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetService passwordResetService;

    @InjectMocks
    private AdminRecoveryRunner runner;

    private static UserSummary summary(String id, boolean active, boolean admin) {
        return new UserSummary(id, id + "@example.com", Instant.now(), active, admin, null, 0, null);
    }

    private static User user(String id, boolean admin) {
        return new User(id, id + "@example.com", "k", "h", Instant.now(), true, admin);
    }

    @Test
    void issuesAnUndeliveredLinkForEachActiveAdminOnly() {
        when(userRepository.findAll()).thenReturn(List.of(
                summary("admin1", true, true),
                summary("admin2", true, true),
                summary("former", false, true),
                summary("regular", true, false)));
        User admin1 = user("admin1", true);
        User admin2 = user("admin2", true);
        when(userRepository.findById("admin1")).thenReturn(Optional.of(admin1));
        when(userRepository.findById("admin2")).thenReturn(Optional.of(admin2));
        when(passwordResetService.issueToken(any(), anyBoolean())).thenReturn(
                new PasswordResetService.IssuedToken("t", "https://x/dashboard?reset_token=t", Instant.now()));

        runner.run(null);

        verify(passwordResetService).issueToken(admin1, false);
        verify(passwordResetService).issueToken(admin2, false);
        verify(passwordResetService, never()).issueToken(org.mockito.ArgumentMatchers.argThat(
                u -> u != null && (u.id().equals("former") || u.id().equals("regular"))), anyBoolean());
    }

    @Test
    void noAdmins_issuesNothing() {
        when(userRepository.findAll()).thenReturn(List.of(summary("regular", true, false)));

        runner.run(null);

        verify(passwordResetService, never()).issueToken(any(), anyBoolean());
    }
}
