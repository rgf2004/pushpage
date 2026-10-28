package me.projects.pushpage.service;

import me.projects.pushpage.model.ClientConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the public configuration exposed at {@code GET /config}. To make a new server
 * setting visible to the frontend, add it here (keys are snake_case) and document it in
 * docs/api.md. Only expose values that are safe for anonymous callers.
 */
@Service
public class ClientConfigService {

    private final boolean plansEnabled;
    private final boolean emailVerificationEnabled;

    public ClientConfigService(
            @Value("${app.features.plans.enabled:false}") boolean plansEnabled,
            @Value("${app.features.email-verification.enabled:false}") boolean emailVerificationEnabled) {
        this.plansEnabled = plansEnabled;
        this.emailVerificationEnabled = emailVerificationEnabled;
    }

    public ClientConfigResponse getConfig() {
        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("plans", plansEnabled);
        features.put("email_verification", emailVerificationEnabled);
        return new ClientConfigResponse(features);
    }
}
