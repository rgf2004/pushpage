package me.projects.pushpage.plans.service;

import jakarta.annotation.PostConstruct;
import me.projects.pushpage.plans.model.Plan;
import me.projects.pushpage.plans.repository.PlanRepository;
import me.projects.pushpage.model.User;
import me.projects.pushpage.service.RetentionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import me.projects.pushpage.plans.ConditionalOnPlansEnabled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service("cloudRetentionPolicy")
@Primary
@ConditionalOnPlansEnabled
public class RetentionService implements RetentionPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final PlanRepository planRepository;

    public RetentionService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @PostConstruct
    void logActivation() {
        log.info("RetentionPolicy active: {} (plan-based retention, overrides NoOpRetentionPolicy)",
                getClass().getSimpleName());
    }

    @Override
    public Instant expiresAt(User user) {
        return Instant.now().plus(resolvePlan(user).retentionDays, ChronoUnit.DAYS);
    }

    private Plan resolvePlan(User user) {
        String planName = planRepository.findPlanByUserId(user.id());
        try {
            return Plan.valueOf(planName);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Plan.tier1;
        }
    }
}
