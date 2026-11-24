package me.projects.pushpage.model;

/**
 * What the public health endpoint returns: only whether the service is up. Operational details
 * (version, uptime, page counts, publish times, disk space) are admin-only; see {@link HealthResponse}.
 */
public record PublicHealthResponse(String status) {}
