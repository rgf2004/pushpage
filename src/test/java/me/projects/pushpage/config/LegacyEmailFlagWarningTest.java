package me.projects.pushpage.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyEmailFlagWarningTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(LegacyEmailFlagWarning.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void warnsWhenOnlyDeprecatedFlagIsSet() {
        new LegacyEmailFlagWarning(new MockEnvironment().withProperty("EMAIL_VERIFICATION_ENABLED", "true"))
                .warnIfDeprecatedFlagUsed();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("EMAIL_ENABLED");
    }

    @Test
    void silentWhenNewFlagIsSet() {
        new LegacyEmailFlagWarning(new MockEnvironment()
                .withProperty("EMAIL_VERIFICATION_ENABLED", "true")
                .withProperty("EMAIL_ENABLED", "true")).warnIfDeprecatedFlagUsed();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void silentWhenNeitherFlagIsSet() {
        new LegacyEmailFlagWarning(new MockEnvironment()).warnIfDeprecatedFlagUsed();

        assertThat(appender.list).isEmpty();
    }
}
