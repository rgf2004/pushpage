package me.projects.pushpage.service;

import me.projects.pushpage.model.User;

/**
 * The verified identity in a JWT, plus the password version it was issued under. A password
 * change moves the user's version on, so every token issued before it stops matching.
 */
public record JwtClaims(String userId, long passwordVersion, java.util.Set<String> scopes, String grantId) {

    /** A dashboard session: no scope restriction. */
    public JwtClaims(String userId, long passwordVersion) {
        this(userId, passwordVersion, null, null);
    }

    /** Null for a normal session; the granted scopes for an OAuth access token. */
    public boolean isScoped() {
        return scopes != null;
    }


    /** 0 until the user's password has changed since sign-up, then the change time in epoch millis. */
    public static long passwordVersionOf(User user) {
        return user.passwordChangedAt() == null ? 0L : user.passwordChangedAt().toEpochMilli();
    }

    public boolean matchesCurrentPassword(User user) {
        return passwordVersion == passwordVersionOf(user);
    }
}
