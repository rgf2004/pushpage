package me.projects.pushpage.cloud.controller;

import me.projects.pushpage.cloud.repository.PlanRepository;
import me.projects.pushpage.cloud.service.EmailVerificationService;
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
class CloudAdminControllerTest {

    static final User USER = new User("u1", "u@example.com", "k", "h", Instant.now(), true, false);

    @Mock EmailVerificationService verificationService;
    @Mock UserRepository userRepository;
    @Mock PlanRepository planRepository;

    @InjectMocks
    CloudAdminController controller;

    // ── changePlan ───────────────────────────────────────────────────────────

    @Test
    void changePlan_withValidPlan_returns204() {
        when(planRepository.changePlan("u1", "tier2")).thenReturn(true);

        var response = controller.changePlan("u1", "tier2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(planRepository).changePlan("u1", "tier2");
    }

    @Test
    void changePlan_withInvalidPlan_throws400() {
        assertThatThrownBy(() -> controller.changePlan("u1", "gold"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(planRepository);
    }

    @Test
    void changePlan_whenUserNotFound_throws404() {
        when(planRepository.changePlan("u1", "tier2")).thenReturn(false);

        assertThatThrownBy(() -> controller.changePlan("u1", "tier2"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void changePlan_acceptsAllValidTiers() {
        when(planRepository.changePlan("u1", "tier1")).thenReturn(true);
        when(planRepository.changePlan("u1", "tier3")).thenReturn(true);

        assertThat(controller.changePlan("u1", "tier1").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.changePlan("u1", "tier3").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

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
