package me.projects.pushpage.service;

import me.projects.pushpage.model.User;

/**
 * Extension point for reacting to user lifecycle events. The default implementation
 * is a no-op; deployments that need to act on these events (e.g. email verification
 * gating in the managed cloud offering) provide their own {@code @Primary} bean.
 */
public interface UserLifecycleHooks {

    /**
     * Called after a new account has been persisted during sign-up.
     */
    default void afterSignUp(User user) {
    }

    /**
     * Called after credentials have been verified during login, before a JWT is issued.
     * Implementations may throw a {@code ResponseStatusException} to block the login.
     */
    default void beforeLogin(User user) {
    }

    /**
     * Called after the user redeemed a password-reset token that was delivered to their
     * mailbox, which proves they own the address.
     */
    default void afterEmailOwnershipProven(User user) {
    }
}
