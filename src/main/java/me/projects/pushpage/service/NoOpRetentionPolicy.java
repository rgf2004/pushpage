package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Default {@link RetentionPolicy} used when no cloud bean overrides it. Self-hosted
 * deployments have no plans, so every authenticated user gets the same global retention
 * window.
 */
@Service
public class NoOpRetentionPolicy implements RetentionPolicy {

    @Value("${app.cleanup.retention-days:30}")
    private int retentionDays;

    @Override
    public Instant expiresAt(User user) {
        return Instant.now().plus(retentionDays, ChronoUnit.DAYS);
    }
}
