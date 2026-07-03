package me.projects.pushpage.service;

import org.springframework.stereotype.Service;

/**
 * Default {@link UserSummaryEnricher} used when no cloud bean overrides it.
 * Returns the list unchanged.
 */
@Service
public class NoOpUserSummaryEnricher implements UserSummaryEnricher {}
