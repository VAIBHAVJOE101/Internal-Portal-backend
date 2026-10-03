package com.platform.portal.kafka;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.platform.portal.alerts.Alert;
import com.platform.portal.alerts.AlertRuleService;
import com.platform.portal.alerts.AlertService;
import com.platform.portal.alerts.AlertType;
import com.platform.portal.config.PortalProperties;
import com.platform.portal.kafka.KafkaModels.ClusterOverview;
import com.platform.portal.kafka.KafkaModels.Connector;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupSummary;
import com.platform.portal.kafka.KafkaModels.KafkaInstance;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Periodically checks every enabled Kafka instance: broker availability, under-replicated partitions,
 * failed connectors / sink tasks and consumer lag. Stores a health snapshot and raises or resolves alerts.
 */
@Component
public class KafkaHealthJob {

    private static final Logger log = LoggerFactory.getLogger(KafkaHealthJob.class);
    private static final long DEFAULT_LAG_THRESHOLD = 10_000;

    private final KafkaInstanceResolver instances;
    private final KafkaGateway gateway;
    private final AlertService alerts;
    private final AlertRuleService rules;
    private final KafkaHealthSnapshot.Repository snapshots;
    private final LockingTaskExecutor locks;
    private final TaskScheduler scheduler;
    private final PortalProperties properties;

    public KafkaHealthJob(KafkaInstanceResolver instances, KafkaGateway gateway, AlertService alerts, AlertRuleService rules,
                          KafkaHealthSnapshot.Repository snapshots, LockingTaskExecutor locks, TaskScheduler scheduler,
                          PortalProperties properties) {
        this.instances = instances;
        this.gateway = gateway;
        this.alerts = alerts;
        this.rules = rules;
        this.snapshots = snapshots;
        this.locks = locks;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void schedule() {
        Duration interval = properties.jobs().kafkaHealthInterval();
        scheduler.scheduleWithFixedDelay(this::runLocked, Instant.now().plusSeconds(5), interval);
    }

    void runLocked() {
        Duration interval = properties.jobs().kafkaHealthInterval();
        locks.executeWithLock((Runnable) this::checkAll,
                new LockConfiguration(Instant.now(), "kafka-health", interval.multipliedBy(5), interval.dividedBy(2)));
    }

    public void checkAll() {
        try {
            for (KafkaInstance instance : instances.enabled()) {
                check(instance);
            }
            pruneHistory();
        } catch (RuntimeException e) {
            log.warn("Kafka health check failed", e);
        }
    }

    void check(KafkaInstance instance) {
        String prefix = "kafka:" + instance.id() + ":";
        Set<String> active = new HashSet<>();
        String resource = instance.name() + " (" + instance.environment() + ")";
        if (instance.brokers().isEmpty()) {
            return;
        }
        ClusterOverview overview = gateway.overview(instance);
        long maxLag = 0;

        if ("UNREACHABLE".equals(overview.status())) {
            raise(active, AlertType.KAFKA_UNREACHABLE, prefix + "unreachable", null, "Kafka cluster " + instance.name() + " is unreachable",
                    overview.error(), resource);
        } else {
            if (overview.brokersOnline() < overview.brokersExpected()) {
                raise(active, AlertType.KAFKA_BROKER_DOWN, prefix + "brokers", overview.brokersOnline() == 0 ? Alert.Severity.CRITICAL : null,
                        "%d of %d brokers online on %s".formatted(overview.brokersOnline(), overview.brokersExpected(), instance.name()),
                        "Brokers configured in Inventory: " + String.join(", ", instance.brokers()), resource);
            }
            if (overview.underReplicated() > 0) {
                raise(active, AlertType.KAFKA_UNDER_REPLICATED, prefix + "urp", null,
                        overview.underReplicated() + " under-replicated partitions on " + instance.name(), null, resource);
            }
            if (overview.offlinePartitions() > 0) {
                raise(active, AlertType.KAFKA_OFFLINE_PARTITIONS, prefix + "offline", null,
                        overview.offlinePartitions() + " offline partitions on " + instance.name(), null, resource);
            }
            if (!instance.connectUrls().isEmpty()) {
                try {
                    for (Connector c : gateway.connectors(instance)) {
                        if (RealKafkaGateway.isFailed(c)) {
                            String failedTasks = c.tasks().stream().filter(t -> "FAILED".equals(t.state()))
                                    .map(t -> "task " + t.id()).reduce((a, b) -> a + ", " + b).orElse("connector");
                            String trace = c.tasks().stream().map(KafkaModels.ConnectorTask::trace).filter(t -> t != null).findFirst().orElse(null);
                            raise(active, AlertType.KAFKA_CONNECTOR_FAILED, prefix + "connector:" + c.name(), null,
                                    "%s %s FAILED (%s) on %s".formatted(capitalize(c.type()), c.name(), failedTasks, instance.name()),
                                    trace == null ? null : trace.lines().findFirst().orElse(trace), resource + " / " + c.name());
                        }
                    }
                } catch (RuntimeException e) {
                    raise(active, AlertType.KAFKA_CONNECT_UNREACHABLE, prefix + "connect", null, "Kafka Connect unreachable for " + instance.name(),
                            e.getMessage(), resource);
                }
            }
            AlertRuleService.Policy lagPolicy = rules.policy(AlertType.KAFKA_CONSUMER_LAG);
            long threshold = lagThreshold(instance, lagPolicy.param("lagThreshold", DEFAULT_LAG_THRESHOLD));
            long criticalAt = threshold * Math.max(1, lagPolicy.param("criticalMultiplier", 10));
            try {
                List<ConsumerGroupSummary> groups = gateway.consumerGroups(instance);
                for (ConsumerGroupSummary g : groups) {
                    maxLag = Math.max(maxLag, g.totalLag());
                    if (g.totalLag() > threshold && g.members() > 0) {
                        raise(active, AlertType.KAFKA_CONSUMER_LAG, prefix + "lag:" + g.groupId(), g.totalLag() > criticalAt ? Alert.Severity.CRITICAL : null,
                                "Consumer group %s lag %,d on %s".formatted(g.groupId(), g.totalLag(), instance.name()),
                                "Warning threshold %,d, critical %,d. Topics: ".formatted(threshold, criticalAt) + String.join(", ", g.topics()), resource + " / " + g.groupId());
                    }
                }
            } catch (RuntimeException e) {
                log.debug("Lag check failed for {}: {}", instance.name(), e.getMessage());
            }
        }
        alerts.resolveMissing(prefix, active);
        saveSnapshot(instance, overview, maxLag);
    }

    private void raise(Set<String> active, AlertType type, String key, Alert.Severity severity, String title, String message, String resource) {
        active.add(key);
        alerts.raise(type, key, severity, title, message, resource);
    }

    void saveSnapshot(KafkaInstance instance, ClusterOverview o, long maxLag) {
        KafkaHealthSnapshot s = new KafkaHealthSnapshot();
        s.setInstanceId(instance.id());
        s.setInstanceName(instance.name());
        s.setTs(Instant.now());
        s.setStatus(o.status());
        s.setBrokersOnline(o.brokersOnline());
        s.setBrokersTotal(o.brokersExpected());
        s.setTopics(o.topics());
        s.setUnderReplicated(o.underReplicated());
        s.setConnectorsTotal(o.connectorsTotal());
        s.setConnectorsFailed(o.connectorsFailed());
        s.setMaxLag(maxLag);
        s.setMessage(o.error());
        snapshots.save(s);
    }

    void pruneHistory() {
        snapshots.deleteOlderThan(Instant.now().minus(7, ChronoUnit.DAYS));
    }

    /** Per-instance override (custom "lagThreshold" inventory column) wins over the alert rule threshold. */
    private static long lagThreshold(KafkaInstance instance, long ruleThreshold) {
        Object v = instance.extra().get("lagThreshold");
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? ruleThreshold : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return ruleThreshold;
        }
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "Connector" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
