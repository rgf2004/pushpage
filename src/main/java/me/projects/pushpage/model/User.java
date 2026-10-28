package me.projects.pushpage.model;

import java.time.Instant;

public record User(
        String id,
        String email,
        String apiKeyHash,
        String passwordHash,
        Instant createdAt,
        boolean active,
        boolean admin,
        Instant passwordChangedAt
) {
    public User(String id, String email, String apiKeyHash, String passwordHash,
                Instant createdAt, boolean active, boolean admin) {
        this(id, email, apiKeyHash, passwordHash, createdAt, active, admin, null);
    }
}
