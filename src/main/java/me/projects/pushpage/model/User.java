package me.projects.pushpage.model;

import java.time.Instant;

public record User(
        String id,
        String username,
        String apiKey,
        Instant createdAt,
        boolean active,
        boolean admin
) {}
