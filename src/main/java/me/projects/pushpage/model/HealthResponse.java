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
        StorageInfo storage
) {
    public record StorageInfo(
            long usedBytes,
            String usedHuman,
            long freeBytes,
            String freeHuman
    ) {}
}
