package me.projects.pushpage.service;

import me.projects.pushpage.emailverification.repository.EmailVerificationRepository;
import me.projects.pushpage.plans.repository.PlanRepository;
import me.projects.pushpage.model.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureUserSummaryEnricherTest {

    @Mock
    PlanRepository planRepository;

    @Mock
    EmailVerificationRepository emailVerificationRepository;

    FeatureUserSummaryEnricher enricher;

    private static UserSummary summary(String id) {
        return new UserSummary(id, id + "@example.com", Instant.now(), true, false, null, 0, null);
    }

    @BeforeEach
    void setUp() {
        enricher = new FeatureUserSummaryEnricher(Optional.of(planRepository), Optional.of(emailVerificationRepository));
        lenient().when(emailVerificationRepository.findVerifiedStatusByUserIds(anyList())).thenReturn(Map.of());
    }

    @Test
    void enrich_withEmptyList_returnsEmptyAndMakesBulkCall() {
        when(planRepository.findPlansByUserIds(List.of())).thenReturn(Map.of());

        List<UserSummary> result = enricher.enrich(List.of());

        assertThat(result).isEmpty();
        verify(planRepository).findPlansByUserIds(List.of());
        verify(emailVerificationRepository).findVerifiedStatusByUserIds(List.of());
    }

    @Test
    void enrich_fillsPlanFromRepository() {
        when(planRepository.findPlansByUserIds(List.of("u1", "u2")))
                .thenReturn(Map.of("u1", "tier1", "u2", "tier3"));

        List<UserSummary> result = enricher.enrich(List.of(summary("u1"), summary("u2")));

        assertThat(result).extracting(UserSummary::plan)
                .containsExactly("tier1", "tier3");
    }

    @Test
    void enrich_whenUserMissingFromPlanMap_fallsBackToTier1() {
        when(planRepository.findPlansByUserIds(List.of("u1"))).thenReturn(Map.of());

        List<UserSummary> result = enricher.enrich(List.of(summary("u1")));

        assertThat(result.get(0).plan()).isEqualTo("tier1");
    }

    @Test
    void enrich_makesExactlyOneBulkQuery() {
        when(planRepository.findPlansByUserIds(anyList())).thenReturn(Map.of("u1", "tier1"));

        enricher.enrich(List.of(summary("u1")));

        verify(planRepository, times(1)).findPlansByUserIds(anyList());
        verify(emailVerificationRepository, times(1)).findVerifiedStatusByUserIds(anyList());
    }

    @Test
    void enrich_fillsEmailVerifiedFromRepository() {
        when(planRepository.findPlansByUserIds(List.of("u1", "u2"))).thenReturn(Map.of());
        when(emailVerificationRepository.findVerifiedStatusByUserIds(List.of("u1", "u2")))
                .thenReturn(Map.of("u1", true, "u2", false));

        List<UserSummary> result = enricher.enrich(List.of(summary("u1"), summary("u2")));

        assertThat(result).extracting(UserSummary::emailVerified)
                .containsExactly(true, false);
    }

    @Test
    void enrich_whenUserMissingFromVerifiedMap_fallsBackToFalse() {
        when(planRepository.findPlansByUserIds(List.of("u1"))).thenReturn(Map.of());
        when(emailVerificationRepository.findVerifiedStatusByUserIds(List.of("u1"))).thenReturn(Map.of());

        List<UserSummary> result = enricher.enrich(List.of(summary("u1")));

        assertThat(result.get(0).emailVerified()).isFalse();
    }

    @Test
    void enrich_preservesAllOtherFields() {
        Instant ts = Instant.parse("2025-01-01T00:00:00Z");
        UserSummary original = new UserSummary("u1", "u1@example.com", ts, false, true, null, 4, null);
        when(planRepository.findPlansByUserIds(anyList())).thenReturn(Map.of("u1", "tier2"));
        when(emailVerificationRepository.findVerifiedStatusByUserIds(anyList())).thenReturn(Map.of("u1", true));

        UserSummary enriched = enricher.enrich(List.of(original)).get(0);

        assertThat(enriched.id()).isEqualTo("u1");
        assertThat(enriched.email()).isEqualTo("u1@example.com");
        assertThat(enriched.createdAt()).isEqualTo(ts);
        assertThat(enriched.active()).isFalse();
        assertThat(enriched.admin()).isTrue();
        assertThat(enriched.plan()).isEqualTo("tier2");
        assertThat(enriched.activePageCount()).isEqualTo(4);
        assertThat(enriched.emailVerified()).isTrue();
    }

    @Test
    void enrich_withOnlyPlansEnabled_leavesEmailVerifiedUntouched() {
        var plansOnly = new FeatureUserSummaryEnricher(Optional.of(planRepository), Optional.empty());
        when(planRepository.findPlansByUserIds(List.of("u1"))).thenReturn(Map.of("u1", "tier2"));

        UserSummary enriched = plansOnly.enrich(List.of(summary("u1"))).get(0);

        assertThat(enriched.plan()).isEqualTo("tier2");
        assertThat(enriched.emailVerified()).isNull();
        verifyNoInteractions(emailVerificationRepository);
    }

    @Test
    void enrich_withOnlyEmailVerificationEnabled_leavesPlanUntouched() {
        var verificationOnly = new FeatureUserSummaryEnricher(Optional.empty(), Optional.of(emailVerificationRepository));
        when(emailVerificationRepository.findVerifiedStatusByUserIds(List.of("u1"))).thenReturn(Map.of("u1", true));

        UserSummary enriched = verificationOnly.enrich(List.of(summary("u1"))).get(0);

        assertThat(enriched.plan()).isNull();
        assertThat(enriched.emailVerified()).isTrue();
        verifyNoInteractions(planRepository);
    }
}
