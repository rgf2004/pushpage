package me.projects.pushpage.service;

import me.projects.pushpage.model.Page;
import me.projects.pushpage.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NoOpRetentionPolicyTest {

    static final User USER = new User("user1", null, "pp_key", null, Instant.now(), true, false);

    private final NoOpRetentionPolicy policy = new NoOpRetentionPolicy();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(policy, "retentionDays", 30);
    }

    @Test
    void expiresAt_whenNotPermanent_returnsRetentionDaysFromNow() {
        Instant result = policy.expiresAt(USER, false);

        assertThat(result).isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void expiresAt_whenPermanent_returnsNoExpirySentinel() {
        Instant result = policy.expiresAt(USER, true);

        assertThat(result).isEqualTo(Page.NO_EXPIRY);
    }

    @Test
    void expiresAt_respectsConfiguredRetentionDays() {
        ReflectionTestUtils.setField(policy, "retentionDays", 7);

        Instant result = policy.expiresAt(USER, false);

        assertThat(result).isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }
}
