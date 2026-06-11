package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public record Page(
        String id,
        String title,
        @JsonProperty("created_at") Instant createdAt,
        String url
) {}
