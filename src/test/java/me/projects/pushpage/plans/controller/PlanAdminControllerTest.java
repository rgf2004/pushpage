package me.projects.pushpage.plans.controller;

import me.projects.pushpage.plans.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlanAdminControllerTest {

    @Mock PlanRepository planRepository;

    @InjectMocks
    PlanAdminController controller;

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
}
