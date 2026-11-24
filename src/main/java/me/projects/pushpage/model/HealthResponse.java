package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse(
        String status,
        String version,
        long uptimeSeconds,
        long livePages,
        long deletedPages,
        String oldestPage,
        String newestPage,
        StorageInfo storage,
        UserInfo users
) {
    /** {@code awaitingVerification} is null (omitted) unless email verification is enabled. */
    public record UserInfo(long total, long active, long inactive, Long awaitingVerification) {}

    public record StorageInfo(
            long usedBytes,
            String usedHuman,
            long freeBytes,
            String freeHuman
    ) {}
}
