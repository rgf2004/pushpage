package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** An API key as listed to its owner (never contains the raw key). */
public record ApiKeyResponse(
        String id,
        String name,
        @JsonProperty("key_prefix") String keyPrefix,
        @JsonProperty("created_at") Instant createdAt,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("last_used_at") Instant lastUsedAt
) {
    public static ApiKeyResponse from(ApiKey key) {
        return new ApiKeyResponse(key.id(), key.name(), key.keyPrefix(), key.createdAt(), key.lastUsedAt());
    }
}
