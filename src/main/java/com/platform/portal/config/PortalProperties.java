package com.platform.portal.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("portal")
public record PortalProperties(
        @DefaultValue("real") String mode,
        @DefaultValue("dev") String environment,
        @DefaultValue("/") String frontendUrl,
        @DefaultValue("http://localhost:5173") String publicUrl,
        String encryptionKey,
        @DefaultValue Auth auth,
        @DefaultValue Jobs jobs,
        Map<String, Map<String, String>> integrations) {

    public record Auth(String githubOrg, @DefaultValue("devops_team") String adminTeamSlug) {
    }

    public record Jobs(@DefaultValue("60s") Duration kafkaHealthInterval,
                       @DefaultValue("6h") Duration expiryCheckInterval) {
    }

    public boolean isMock() {
        return "mock".equalsIgnoreCase(mode);
    }

    public Map<String, String> integrationDefaults(String key) {
        if (integrations == null) {
            return Map.of();
        }
        return integrations.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Map.of());
    }
}
