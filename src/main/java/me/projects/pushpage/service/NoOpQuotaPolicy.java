package me.projects.pushpage.service;

import me.projects.pushpage.model.User;
import org.springframework.stereotype.Service;

/**
 * Default {@link QuotaPolicy} used when no cloud bean overrides it.
 * Self-hosted deployments publish without any daily limit.
 */
@Service
public class NoOpQuotaPolicy implements QuotaPolicy {

    @Override
    public void check(User user) {}
}
