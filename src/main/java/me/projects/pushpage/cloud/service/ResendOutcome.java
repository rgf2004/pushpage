package me.projects.pushpage.cloud.service;

/** Outcome of a resend request — carries what the controller needs to build the HTTP response, nothing more. */
public record ResendOutcome(boolean rateLimited, long retryAfterSeconds) {

    public static ResendOutcome accepted() {
        return new ResendOutcome(false, 0);
    }

    public static ResendOutcome rateLimited(long retryAfterSeconds) {
        return new ResendOutcome(true, retryAfterSeconds);
    }
}
