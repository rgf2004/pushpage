package me.projects.pushpage.model;

import java.time.Instant;

public record User(
        String id,
        String email,
        String apiKeyHash,
        String passwordHash,
        Instant createdAt,
        boolean active,
        boolean admin
) {}
