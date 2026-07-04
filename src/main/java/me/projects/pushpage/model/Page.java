package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record Page(
        String id,
        String title,
        @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        @JsonProperty("deleted_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant deletedAt,
        @JsonProperty("expires_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt,
        String url,
        @JsonProperty("user_id") String userId
) {}
