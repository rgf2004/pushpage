package me.projects.pushpage.cloud.service;

import me.projects.pushpage.cloud.repository.PlanRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    static final User ADMIN = new User("admin1", "admin@example.com", "pp_key", "hash", Instant.now(), true, true);
    static final User USER = new User("user1", "user@example.com", "pp_key2", "hash", Instant.now(), true, false);

    @Mock
    PlanRepository planRepository;

    @InjectMocks
    QuotaService quotaService;

    // ── check() ─────────────────────────────────────────────────────────────

    @Test
    void check_withNullUser_skipsQuota() {
        assertThatCode(() -> quotaService.check(null)).doesNotThrowAnyException();
        verifyNoInteractions(planRepository);
    }

    @Test
    void check_withAdminUser_skipsQuota() {
        assertThatCode(() -> quotaService.check(ADMIN)).doesNotThrowAnyException();
        verifyNoInteractions(planRepository);
    }

    @Test
    void check_whenUnderDailyLimit_doesNotThrow() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier1");
        when(planRepository.countTodayPagesByUser("user1")).thenReturn(29L);

        assertThatCode(() -> quotaService.check(USER)).doesNotThrowAnyException();
    }

    @Test
    void check_whenAtDailyLimit_throws429() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier1");
        when(planRepository.countTodayPagesByUser("user1")).thenReturn(30L);

        assertThatThrownBy(() -> quotaService.check(USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void check_tier2UserUnderTier2Limit_doesNotThrow() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier2");
        when(planRepository.countTodayPagesByUser("user1")).thenReturn(99L);

        assertThatCode(() -> quotaService.check(USER)).doesNotThrowAnyException();
    }

    @Test
    void check_tier2UserAtTier2Limit_throws429() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier2");
        when(planRepository.countTodayPagesByUser("user1")).thenReturn(100L);

        assertThatThrownBy(() -> quotaService.check(USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void check_withUnknownPlanName_fallsBackToTier1Limit() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("enterprise");
        when(planRepository.countTodayPagesByUser("user1")).thenReturn(30L);

        assertThatThrownBy(() -> quotaService.check(USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    // ── dailyUsage() ─────────────────────────────────────────────────────────

    @Test
    void dailyUsage_delegatesToRepository() {
        when(planRepository.countTodayPagesByUser("user1")).thenReturn(7L);

        assertThat(quotaService.dailyUsage("user1")).isEqualTo(7L);
    }

    // ── dailyLimit() ─────────────────────────────────────────────────────────

    @Test
    void dailyLimit_withNullUser_returnsNull() {
        assertThat(quotaService.dailyLimit(null)).isNull();
        verifyNoInteractions(planRepository);
    }

    @Test
    void dailyLimit_withAdminUser_returnsNull() {
        assertThat(quotaService.dailyLimit(ADMIN)).isNull();
        verifyNoInteractions(planRepository);
    }

    @Test
    void dailyLimit_withTier1User_returnsTier1Limit() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier1");

        assertThat(quotaService.dailyLimit(USER)).isEqualTo(30);
    }

    @Test
    void dailyLimit_withTier2User_returnsTier2Limit() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier2");

        assertThat(quotaService.dailyLimit(USER)).isEqualTo(100);
    }

    @Test
    void dailyLimit_withTier3User_returnsTier3Limit() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier3");

        assertThat(quotaService.dailyLimit(USER)).isEqualTo(500);
    }

    // ── planName() ───────────────────────────────────────────────────────────

    @Test
    void planName_withNullUser_returnsNull() {
        assertThat(quotaService.planName(null)).isNull();
        verifyNoInteractions(planRepository);
    }

    @Test
    void planName_withRegularUser_returnsFromRepository() {
        when(planRepository.findPlanByUserId("user1")).thenReturn("tier2");

        assertThat(quotaService.planName(USER)).isEqualTo("tier2");
    }
}
