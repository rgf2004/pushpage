package me.projects.pushpage.plans.service;

import me.projects.pushpage.plans.repository.PlanRepository;
import me.projects.pushpage.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    static final User USER = new User("user1", "user@example.com", "pp_key", "hash", Instant.now(), true, false);

    @Mock
    PlanRepository planRepository;

    @InjectMocks
    RetentionService retentionService;

    @Test
    void expiresAt_tier1User_returnsSevenDaysFromNow() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier1");

        Instant result = retentionService.expiresAt(USER);

        assertThat(result).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void expiresAt_tier2User_returnsThirtyDaysFromNow() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier2");

        Instant result = retentionService.expiresAt(USER);

        assertThat(result).isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void expiresAt_tier3User_returnsNinetyDaysFromNow() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier3");

        Instant result = retentionService.expiresAt(USER);

        assertThat(result).isCloseTo(Instant.now().plus(90, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void expiresAt_withUnknownPlanName_fallsBackToTier1Retention() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("enterprise");

        Instant result = retentionService.expiresAt(USER);

        assertThat(result).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void expiresAt_withNullPlanName_fallsBackToTier1Retention() {
        when(planRepository.findPlanByUserId("user1")).thenReturn(null);

        Instant result = retentionService.expiresAt(USER);

        assertThat(result).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }
}
