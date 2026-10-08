package me.projects.pushpage;

import me.projects.pushpage.PostgresTestSupport;
import me.projects.pushpage.emailverification.controller.EmailVerificationAdminController;
import me.projects.pushpage.emailverification.controller.EmailVerificationController;
import me.projects.pushpage.plans.controller.PlanAdminController;
import me.projects.pushpage.emailverification.repository.EmailVerificationRepository;
import me.projects.pushpage.plans.repository.PlanRepository;
import me.projects.pushpage.service.FeatureUserSummaryEnricher;
import me.projects.pushpage.emailverification.service.EmailVerificationHooks;
import me.projects.pushpage.plans.service.QuotaService;
import me.projects.pushpage.plans.service.RetentionService;
import me.projects.pushpage.service.NoOpQuotaPolicy;
import me.projects.pushpage.service.NoOpRetentionPolicy;
import me.projects.pushpage.service.NoOpUserLifecycleHooks;
import me.projects.pushpage.service.NoOpUserSummaryEnricher;
import me.projects.pushpage.service.QuotaPolicy;
import me.projects.pushpage.service.RetentionPolicy;
import me.projects.pushpage.service.UserLifecycleHooks;
import me.projects.pushpage.service.UserSummaryEnricher;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Each feature flag must switch on exactly its own beans and nothing else. */
class FeatureFlagsIT {

    @Nested
    @SpringBootTest
    class AllFlagsOff extends PostgresTestSupport {
        @Autowired ApplicationContext ctx;

        @Test
        void usesNoOpDefaultsAndRegistersNoFeatureBeans() {
            assertThat(ctx.getBean(QuotaPolicy.class)).isInstanceOf(NoOpQuotaPolicy.class);
            assertThat(ctx.getBean(RetentionPolicy.class)).isInstanceOf(NoOpRetentionPolicy.class);
            assertThat(ctx.getBean(UserLifecycleHooks.class)).isInstanceOf(NoOpUserLifecycleHooks.class);
            assertThat(ctx.getBean(UserSummaryEnricher.class)).isInstanceOf(NoOpUserSummaryEnricher.class);
            assertThat(ctx.getBeansOfType(PlanRepository.class)).isEmpty();
            assertThat(ctx.getBeansOfType(EmailVerificationRepository.class)).isEmpty();
            assertThat(ctx.getBeansOfType(PlanAdminController.class)).isEmpty();
            assertThat(ctx.getBeansOfType(EmailVerificationController.class)).isEmpty();
            assertThat(ctx.getBeansOfType(EmailVerificationAdminController.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "app.features.plans.enabled=true")
    class PlansOnly extends PostgresTestSupport {
        @Autowired ApplicationContext ctx;

        @Test
        void enablesQuotaAndRetentionButNotEmailVerification() {
            assertThat(ctx.getBean(QuotaPolicy.class)).isInstanceOf(QuotaService.class);
            assertThat(ctx.getBean(RetentionPolicy.class)).isInstanceOf(RetentionService.class);
            assertThat(ctx.getBean(UserSummaryEnricher.class)).isInstanceOf(FeatureUserSummaryEnricher.class);
            assertThat(ctx.getBeansOfType(PlanAdminController.class)).hasSize(1);
            assertThat(ctx.getBean(UserLifecycleHooks.class)).isInstanceOf(NoOpUserLifecycleHooks.class);
            assertThat(ctx.getBeansOfType(EmailVerificationRepository.class)).isEmpty();
            assertThat(ctx.getBeansOfType(EmailVerificationController.class)).isEmpty();
            assertThat(ctx.getBeansOfType(EmailVerificationAdminController.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"app.features.email-verification.enabled=true", "app.email.from=test@example.com", "app.email.verification-token-ttl-hours=24", "app.email.verification-rate-limit-per-minute=1", "app.email.resend-ip-rate-limit-per-minute=5", "spring.mail.host=localhost"})
    class EmailVerificationOnly extends PostgresTestSupport {
        @Autowired ApplicationContext ctx;

        @Test
        void enablesVerificationButNotPlans() {
            assertThat(ctx.getBean(UserLifecycleHooks.class)).isInstanceOf(EmailVerificationHooks.class);
            assertThat(ctx.getBean(UserSummaryEnricher.class)).isInstanceOf(FeatureUserSummaryEnricher.class);
            assertThat(ctx.getBeansOfType(EmailVerificationController.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(EmailVerificationAdminController.class)).hasSize(1);
            assertThat(ctx.getBean(QuotaPolicy.class)).isInstanceOf(NoOpQuotaPolicy.class);
            assertThat(ctx.getBean(RetentionPolicy.class)).isInstanceOf(NoOpRetentionPolicy.class);
            assertThat(ctx.getBeansOfType(PlanRepository.class)).isEmpty();
            assertThat(ctx.getBeansOfType(PlanAdminController.class)).isEmpty();
        }
    }
}
