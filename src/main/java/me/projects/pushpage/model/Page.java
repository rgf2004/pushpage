package me.projects.pushpage.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Page(
        String id,
        String title,
        @JsonProperty("created_at") String createdAt,
        String url
) {}
