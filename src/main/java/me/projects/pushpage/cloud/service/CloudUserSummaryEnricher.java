package me.projects.pushpage.cloud.service;

import jakarta.annotation.PostConstruct;
import me.projects.pushpage.cloud.repository.EmailVerificationRepository;
import me.projects.pushpage.cloud.repository.PlanRepository;
import me.projects.pushpage.model.UserSummary;
import me.projects.pushpage.service.UserSummaryEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Primary
@ConditionalOnExpression("${app.features.plans.enabled:false} or ${app.features.email-verification.enabled:false}")
public class CloudUserSummaryEnricher implements UserSummaryEnricher {

    private static final Logger log = LoggerFactory.getLogger(CloudUserSummaryEnricher.class);

    private final Optional<PlanRepository> planRepository;
    private final Optional<EmailVerificationRepository> emailVerificationRepository;

    // Each repository only exists when its feature flag is on; fields stay null otherwise.
    public CloudUserSummaryEnricher(Optional<PlanRepository> planRepository,
                                     Optional<EmailVerificationRepository> emailVerificationRepository) {
        this.planRepository = planRepository;
        this.emailVerificationRepository = emailVerificationRepository;
    }

    @PostConstruct
    void logActivation() {
        log.info("UserSummaryEnricher active: {} (plans={}, emailVerification={}, overrides NoOpUserSummaryEnricher)",
                getClass().getSimpleName(), planRepository.isPresent(), emailVerificationRepository.isPresent());
    }

    @Override
    public List<UserSummary> enrich(List<UserSummary> summaries) {
        List<String> ids = summaries.stream().map(UserSummary::id).toList();
        Map<String, String> plans = planRepository.map(r -> r.findPlansByUserIds(ids)).orElse(null);
        Map<String, Boolean> verified = emailVerificationRepository.map(r -> r.findVerifiedStatusByUserIds(ids)).orElse(null);
        return summaries.stream()
                .map(s -> new UserSummary(s.id(), s.email(), s.createdAt(), s.active(), s.admin(),
                        plans == null ? s.plan() : plans.getOrDefault(s.id(), "tier1"), s.activePageCount(),
                        verified == null ? s.emailVerified() : verified.getOrDefault(s.id(), false)))
                .toList();
    }
}
