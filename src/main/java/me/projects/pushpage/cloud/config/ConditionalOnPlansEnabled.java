package me.projects.pushpage.cloud.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Active only when {@code PLANS_ENABLED=true} (daily quota + plan-based retention). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "app.features.plans.enabled", havingValue = "true")
public @interface ConditionalOnPlansEnabled {
}
