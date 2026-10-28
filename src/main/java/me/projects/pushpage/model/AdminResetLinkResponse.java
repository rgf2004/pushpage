package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record AdminResetLinkResponse(
        @JsonProperty("reset_link") String resetLink,
        @JsonProperty("expires_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt
) {}
