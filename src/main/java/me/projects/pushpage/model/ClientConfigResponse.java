package me.projects.pushpage.model;

import java.util.Map;

/**
 * Non-sensitive server settings that the browser frontend needs to adapt its UI.
 * Everything here is public, so never add secrets or per-user data.
 */
public record ClientConfigResponse(Map<String, Boolean> features) {}
