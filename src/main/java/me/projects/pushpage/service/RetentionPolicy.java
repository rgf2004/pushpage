package me.projects.pushpage.service;

import me.projects.pushpage.model.User;

import java.time.Instant;

/**
 * Extension point for computing how long a published page is retained. The default
 * implementation applies a single global retention window; cloud deployments provide
 * a {@code @Primary} bean that varies the window by the authenticated user's plan.
 */
public interface RetentionPolicy {

    /**
     * Returns the expiry instant for a page published by {@code user}. Never called for
     * guest (unauthenticated) publishes — those always use the fixed guest expiry window.
     *
     * @param permanentRequested whether the caller asked for the page to never expire.
     *                           Implementations that don't support permanent pages may ignore it.
     */
    Instant expiresAt(User user, boolean permanentRequested);
}
