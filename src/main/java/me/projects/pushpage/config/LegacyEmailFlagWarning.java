package me.projects.pushpage.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Warns when a deployment still configures email with the deprecated {@code EMAIL_VERIFICATION_ENABLED}. */
@Component
public class LegacyEmailFlagWarning {

    private static final Logger log = LoggerFactory.getLogger(LegacyEmailFlagWarning.class);

    private final Environment environment;

    public LegacyEmailFlagWarning(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void warnIfDeprecatedFlagUsed() {
        if (environment.getProperty("EMAIL_VERIFICATION_ENABLED") != null
                && environment.getProperty("EMAIL_ENABLED") == null) {
            log.warn("EMAIL_VERIFICATION_ENABLED is deprecated and will be removed; rename it to EMAIL_ENABLED.");
        }
    }
}
