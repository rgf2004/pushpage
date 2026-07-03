package me.projects.pushpage.service;

import me.projects.pushpage.model.UserSummary;

import java.util.List;

/**
 * Extension point for enriching the admin user list with cloud-only fields
 * (e.g. subscription plan). The default implementation is a pass-through;
 * cloud deployments provide a {@code @Primary} bean that fills in extra fields.
 */
public interface UserSummaryEnricher {
    default List<UserSummary> enrich(List<UserSummary> summaries) {
        return summaries;
    }
}
