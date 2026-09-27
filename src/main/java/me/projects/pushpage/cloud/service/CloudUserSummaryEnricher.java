package me.projects.pushpage.cloud.service;

import jakarta.annotation.PostConstruct;
import me.projects.pushpage.cloud.repository.EmailVerificationRepository;
import me.projects.pushpage.cloud.repository.PlanRepository;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.service.UserSummaryEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Primary
@Profile("cloud")
public class CloudUserSummaryEnricher implements UserSummaryEnricher {

    private static final Logger log = LoggerFactory.getLogger(CloudUserSummaryEnricher.class);

    private final PlanRepository planRepository;
    private final EmailVerificationRepository emailVerificationRepository;

    public CloudUserSummaryEnricher(PlanRepository planRepository,
                                     EmailVerificationRepository emailVerificationRepository) {
        this.planRepository = planRepository;
        this.emailVerificationRepository = emailVerificationRepository;
    }

    @PostConstruct
    void logActivation() {
        log.info("Cloud UserSummaryEnricher active: {} (adds plan + email_verified fields, overrides NoOpUserSummaryEnricher)",
                getClass().getSimpleName());
    }

    @Override
    public List<UserSummary> enrich(List<UserSummary> summaries) {
        List<String> ids = summaries.stream().map(UserSummary::id).toList();
        Map<String, String> plans = planRepository.findPlansByUserIds(ids);
        Map<String, Boolean> verified = emailVerificationRepository.findVerifiedStatusByUserIds(ids);
        return summaries.stream()
                .map(s -> new UserSummary(s.id(), s.email(), s.createdAt(), s.active(), s.admin(),
                        plans.getOrDefault(s.id(), "tier1"), s.activePageCount(),
                        verified.getOrDefault(s.id(), false)))
                .toList();
    }
}
