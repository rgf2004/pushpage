package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record UserSummary(
        String id,
        String email,
        @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
        boolean active,
        boolean admin,
        @JsonInclude(JsonInclude.Include.NON_NULL) String plan,
        @JsonProperty("active_page_count") long activePageCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("email_verified") Boolean emailVerified
) {}
