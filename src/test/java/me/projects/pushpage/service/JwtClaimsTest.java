package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JwtClaimsTest {

    private static User userWithPasswordChangedAt(Instant changedAt) {
        return new User("u1", "a@b.co", "k", "h", Instant.now(), true, false, changedAt);
    }

    @Test
    void neverChangedPassword_isVersionZero() {
        assertThat(JwtClaims.passwordVersionOf(userWithPasswordChangedAt(null))).isZero();
    }

    @Test
    void tokenMatchesWhilePasswordUnchanged() {
        User user = userWithPasswordChangedAt(null);

        assertThat(new JwtClaims("u1", 0).matchesCurrentPassword(user)).isTrue();
    }

    @Test
    void tokenIssuedBeforeAnyChange_stopsMatchingOncePasswordChanges() {
        User changed = userWithPasswordChangedAt(Instant.parse("2026-10-28T10:00:00.123Z"));

        assertThat(new JwtClaims("u1", 0).matchesCurrentPassword(changed)).isFalse();
    }

    @Test
    void tokenIssuedAfterChange_matches_evenWithinTheSameSecond() {
        User changed = userWithPasswordChangedAt(Instant.parse("2026-10-28T10:00:00.123Z"));

        assertThat(new JwtClaims("u1", JwtClaims.passwordVersionOf(changed)).matchesCurrentPassword(changed)).isTrue();
    }

    @Test
    void tokenFromAnEarlierPasswordVersion_doesNotMatchALaterOne() {
        User first = userWithPasswordChangedAt(Instant.parse("2026-10-28T10:00:00.000Z"));
        User second = userWithPasswordChangedAt(Instant.parse("2026-10-28T10:00:00.001Z"));

        assertThat(new JwtClaims("u1", JwtClaims.passwordVersionOf(first)).matchesCurrentPassword(second)).isFalse();
    }
}
