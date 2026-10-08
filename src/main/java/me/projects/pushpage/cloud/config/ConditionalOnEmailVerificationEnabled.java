package me.projects.pushpage.cloud.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Active only when {@code EMAIL_VERIFICATION_ENABLED=true} (sign-up email verification). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "app.features.email-verification.enabled", havingValue = "true")
public @interface ConditionalOnEmailVerificationEnabled {
}
