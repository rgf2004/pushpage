package me.projects.pushpage.service;

import me.projects.pushpage.model.User;

/**
 * Extension point for enforcing per-user publish quotas. The default implementation
 * is a no-op (no limits); cloud deployments provide a {@code @Primary} bean that
 * enforces plan-based daily limits.
 */
public interface QuotaPolicy {

    /** Called before a page is persisted. Implementations may throw {@code 429} to block. */
    void check(User user);

    /** Returns the number of pages the user has published today, or 0 if not tracked. */
    default long dailyUsage(String userId) {
        return 0;
    }

    /** Returns the user's daily page limit, or {@code null} if unlimited. */
    default Integer dailyLimit(User user) {
        return null;
    }

    /** Returns the user's plan name, or {@code null} if plans are not in use. */
    default String planName(User user) {
        return null;
    }
}
