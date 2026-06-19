package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record PublishResponse(
        String url,
        String id,
        @JsonProperty("expires_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt
) {}
