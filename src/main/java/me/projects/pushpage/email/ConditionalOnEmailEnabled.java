package me.projects.pushpage.email;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Active only when {@code EMAIL_ENABLED=true}: SMTP sending, sign-up verification and password-reset emails. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = "app.features.email.enabled", havingValue = "true")
public @interface ConditionalOnEmailEnabled {
}
