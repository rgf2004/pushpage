package me.projects.pushpage.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientConfigServiceTest {

    @Test
    void getConfig_reflectsFeatureFlags() {
        var config = new ClientConfigService(false, true).getConfig();

        assertEquals(Map.of("plans", false, "email", true), config.features());
    }

    @Test
    void getConfig_allDisabled() {
        var config = new ClientConfigService(false, false).getConfig();

        assertEquals(Map.of("plans", false, "email", false), config.features());
    }
}
