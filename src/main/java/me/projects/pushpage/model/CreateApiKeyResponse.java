package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/** Returned once when a key is created: the only time the raw key is available. */
public record CreateApiKeyResponse(
        String id,
        String name,
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("key_prefix") String keyPrefix,
        @JsonProperty("created_at") Instant createdAt
) {}
