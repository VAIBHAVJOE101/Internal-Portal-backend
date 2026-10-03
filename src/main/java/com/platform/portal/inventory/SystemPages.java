package com.platform.portal.inventory;

import java.util.List;
import java.util.Map;

import com.platform.portal.inventory.InventoryDtos.ColumnRequest;

/**
 * Inventory pages that other modules depend on. They are created on startup, cannot be deleted
 * and their core columns are locked (users may still add extra columns).
 */
public final class SystemPages {

    public static final String KAFKA_INSTANCES = "kafka-instances";

    private SystemPages() {
    }

    public record Definition(String slug, String name, String description, String icon, String group, List<ColumnRequest> columns) {
    }

    public static final Definition KAFKA = new Definition(KAFKA_INSTANCES, "Kafka Instances",
            "Kafka clusters managed by the portal. The Kafka module connects using the broker and Connect/sink addresses below.",
            "server-cog", "Platform", List.of(
            col("name", "Name", ColumnType.TEXT, true, null, "Display name of the cluster"),
            col("environment", "Environment", ColumnType.SELECT, true, choices("dev", "qa", "uat", "prod"), null),
            col("brokers", "Broker IPs / hosts", ColumnType.LIST, true, null, "host:port of each bootstrap broker, e.g. 10.20.1.11:9092"),
            col("connectUrls", "Connect / sink IPs", ColumnType.LIST, false, null,
                    "Kafka Connect REST endpoints used for connectors and sinks, e.g. http://10.20.2.21:8083"),
            col("securityProtocol", "Security protocol", ColumnType.SELECT, true, choices("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"), null),
            col("saslMechanism", "SASL mechanism", ColumnType.SELECT, false, choices("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"), null),
            col("credentialRef", "Credential ref", ColumnType.TEXT, false, null,
                    "Key of a Kafka credential stored under Settings (never put passwords here)"),
            col("enabled", "Enabled", ColumnType.BOOLEAN, false, null, "Disabled instances are hidden from the Kafka page and health checks")));

    public static List<Definition> all() {
        return List.of(KAFKA);
    }

    private static ColumnRequest col(String key, String label, ColumnType type, boolean required, Map<String, Object> options, String description) {
        return new ColumnRequest(key, label, type, required, true, null, options, false, description);
    }

    static Map<String, Object> choices(String... values) {
        return Map.of("choices", java.util.Arrays.stream(values).map(v -> Map.<String, Object>of("value", v)).toList());
    }
}
