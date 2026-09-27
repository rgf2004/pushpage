package me.projects.pushpage.cloud.service;

import jakarta.annotation.PostConstruct;
import me.projects.pushpage.cloud.model.Plan;
import me.projects.pushpage.cloud.repository.PlanRepository;
import me.projects.pushpage.model.User;
import me.projects.pushpage.service.QuotaPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service("cloudQuotaPolicy")
@Primary
@Profile("cloud")
public class QuotaService implements QuotaPolicy {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final PlanRepository planRepository;

    public QuotaService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    @PostConstruct
    void logActivation() {
        log.info("Cloud QuotaPolicy active: {} (plan-based daily quotas, overrides NoOpQuotaPolicy)",
                getClass().getSimpleName());
    }

    @Override
    public void check(User user) {
        if (user == null || user.admin()) {
            return;
        }
        Plan plan = resolvePlan(user);
        long used = planRepository.countTodayPagesByUser(user.id());
        if (used >= plan.dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Daily publish limit reached (%d/%d). Upgrade your plan for a higher limit."
                            .formatted(used, plan.dailyLimit));
        }
    }

    @Override
    public long dailyUsage(String userId) {
        return planRepository.countTodayPagesByUser(userId);
    }

    @Override
    public Integer dailyLimit(User user) {
        if (user == null || user.admin()) {
            return null;
        }
        return resolvePlan(user).dailyLimit;
    }

    @Override
    public String planName(User user) {
        if (user == null) {
            return null;
        }
        return planRepository.findPlanByUserId(user.id());
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
