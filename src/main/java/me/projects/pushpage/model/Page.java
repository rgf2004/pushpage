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
) {

    /**
     * Sentinel stored in the {@code expires_at} column for pages that never expire.
     * A real (distant) timestamp is used instead of {@code NULL} because {@code NULL}
     * already carries a different meaning: rows written before the column existed,
     * which the cleanup job still sweeps up via a created-at fallback (see
     * {@code PageRepository#findExpired}).
     */
    public static final Instant NO_EXPIRY = Instant.parse("9999-12-31T23:59:59Z");

    /** Maps the internal {@link #NO_EXPIRY} sentinel to {@code null} for external consumers. */
    public static Instant toExternalExpiresAt(Instant expiresAt) {
        return NO_EXPIRY.equals(expiresAt) ? null : expiresAt;
    }
}
