package com.platform.portal.alerts;

import java.util.List;
import java.util.Map;

import com.platform.portal.alerts.Alert.Severity;

/**
 * Catalogue of everything the portal can alert on. Each type carries sensible default policy values;
 * administrators override them per type under Alerts → Rules (stored in {@code alert_rule}).
 */
public enum AlertType {

    KAFKA_UNREACHABLE("Kafka cluster unreachable", "KAFKA", Severity.CRITICAL,
            "Admin API cannot reach any broker of a registered Kafka instance.",
            new Defaults(2, 60, 15, 30, 60, List.of(new EscalationStep(15, List.of(), true, true)))),
    KAFKA_BROKER_DOWN("Kafka broker offline", "KAFKA", Severity.WARNING,
            "Fewer brokers online than registered in Inventory → Kafka Instances.",
            new Defaults(2, 120, 30, 30, 120, List.of(new EscalationStep(30, List.of(), true, true)))),
    KAFKA_UNDER_REPLICATED("Under-replicated partitions", "KAFKA", Severity.WARNING,
            "Partitions whose in-sync replica set is smaller than the replica set.",
            new Defaults(3, 300, 60, 30, 120, List.of())),
    KAFKA_OFFLINE_PARTITIONS("Offline partitions", "KAFKA", Severity.CRITICAL,
            "Partitions without a leader – producers and consumers are blocked.",
            new Defaults(1, 0, 15, 30, 60, List.of(new EscalationStep(10, List.of(), true, true)))),
    KAFKA_CONNECTOR_FAILED("Connector / sink task failed", "KAFKA", Severity.CRITICAL,
            "A Kafka Connect connector or one of its tasks is in FAILED state.",
            new Defaults(1, 60, 30, 30, 120, List.of(new EscalationStep(60, List.of(), true, false)))),
    KAFKA_CONNECT_UNREACHABLE("Kafka Connect unreachable", "KAFKA", Severity.WARNING,
            "None of the Connect / sink IPs of an instance answer.",
            new Defaults(2, 120, 30, 30, 120, List.of())),
    KAFKA_CONSUMER_LAG("Consumer lag above threshold", "KAFKA", Severity.WARNING,
            "Total lag of an active consumer group exceeds the threshold (critical at threshold × multiplier).",
            new Defaults(3, 300, 60, 60, 120, List.of()),
            Map.of("lagThreshold", 10_000, "criticalMultiplier", 10)),
    CONNECTIVITY_FAILURE("Connectivity check failing", "CONNECTIVITY", Severity.CRITICAL,
            "A scheduled connectivity test failed more times in a row than its target's threshold.",
            new Defaults(1, 0, 30, 0, 60, List.of(new EscalationStep(30, List.of(), true, false)))),
    CREDENTIAL_EXPIRY("Credential / certificate expiring", "INVENTORY", Severity.WARNING,
            "An expiry-tracked inventory date (secret, certificate, licence…) is close or past.",
            new Defaults(1, 0, 1440, 0, 2 * 1440 + 60, List.of()),
            Map.of("warnDays", 30, "criticalDays", 7));

    private final String label;
    private final String source;
    private final Severity defaultSeverity;
    private final String description;
    private final Defaults defaults;
    private final Map<String, Object> defaultParams;

    AlertType(String label, String source, Severity severity, String description, Defaults defaults) {
        this(label, source, severity, description, defaults, Map.of());
    }

    AlertType(String label, String source, Severity severity, String description, Defaults defaults, Map<String, Object> params) {
        this.label = label;
        this.source = source;
        this.defaultSeverity = severity;
        this.description = description;
        this.defaults = defaults;
        this.defaultParams = params;
    }

    public String label() { return label; }
    public String source() { return source; }
    public Severity defaultSeverity() { return defaultSeverity; }
    public String description() { return description; }
    public Defaults defaults() { return defaults; }
    public Map<String, Object> defaultParams() { return defaultParams; }

    /**
     * @param minOccurrences   how many consecutive detections are needed before the alert fires
     * @param pendingSeconds   how long the condition must persist before firing ("for" duration)
     * @param repeatMinutes    first re-notification interval while unacknowledged (then backoff)
     * @param resolveGraceSecs how long the condition must stay clear before auto-resolving
     * @param staleMinutes     auto-resolve when the condition is no longer reported for this long (0 = off)
     */
    public record Defaults(int minOccurrences, int pendingSeconds, int repeatMinutes, int resolveGraceSecs, int staleMinutes,
                           List<EscalationStep> escalation) {
    }

    /** Escalate when the alert is still unacknowledged {@code afterMinutes} after it fired. */
    public record EscalationStep(int afterMinutes, List<String> emails, boolean teams, boolean raiseToCritical) {
    }
}
