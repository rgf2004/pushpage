package me.projects.pushpage.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves {@code app.features.email.enabled} against the real main application.properties
 * (the test classpath has its own application.properties that shadows it).
 */
class EmailFlagResolutionTest {

    private static String resolve(String emailEnabled, String legacyEmailVerificationEnabled) throws IOException {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addLast(new PropertiesPropertySource("main",
                PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/application.properties"))));
        if (emailEnabled != null) env.setProperty("EMAIL_ENABLED", emailEnabled);
        if (legacyEmailVerificationEnabled != null) env.setProperty("EMAIL_VERIFICATION_ENABLED", legacyEmailVerificationEnabled);
        return env.getProperty("app.features.email.enabled");
    }

    @Test
    void defaultsToDisabled() throws IOException {
        assertThat(resolve(null, null)).isEqualTo("false");
    }

    @Test
    void emailEnabledEnablesEmail() throws IOException {
        assertThat(resolve("true", null)).isEqualTo("true");
    }

    @Test
    void deprecatedFlagStillEnablesEmail() throws IOException {
        assertThat(resolve(null, "true")).isEqualTo("true");
    }

    @Test
    void newFlagWinsOverDeprecatedFlag() throws IOException {
        assertThat(resolve("false", "true")).isEqualTo("false");
    }
}
