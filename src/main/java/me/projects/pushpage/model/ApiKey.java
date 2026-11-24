package me.projects.pushpage.model;

import java.time.Instant;

/** An API key's metadata. The raw key is never stored; only its hash is. */
public record ApiKey(
        String id,
        String userId,
        String name,
        String keyPrefix,
        Instant createdAt,
        Instant lastUsedAt
) {}
