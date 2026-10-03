package com.platform.portal.kafka;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.kafka.KafkaModels.AlterConfigsRequest;
import com.platform.portal.kafka.KafkaModels.BrokerInfo;
import com.platform.portal.kafka.KafkaModels.ClusterOverview;
import com.platform.portal.kafka.KafkaModels.Connector;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupDetail;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupSummary;
import com.platform.portal.kafka.KafkaModels.CreateTopicRequest;
import com.platform.portal.kafka.KafkaModels.KafkaInstance;
import com.platform.portal.kafka.KafkaModels.RawResponse;
import com.platform.portal.kafka.KafkaModels.ResetOffsetsRequest;
import com.platform.portal.kafka.KafkaModels.TopicDetail;
import com.platform.portal.kafka.KafkaModels.TopicSummary;
import org.springframework.stereotype.Service;

/** Kafka operations with validation and audit logging on top of the active {@link KafkaGateway}. */
@Service
public class KafkaService {

    private static final Set<String> RAW_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private final KafkaInstanceResolver instances;
    private final KafkaGateway gateway;
    private final AuditService audit;
    private final KafkaHealthSnapshot.Repository snapshots;

    public KafkaService(KafkaInstanceResolver instances, KafkaGateway gateway, AuditService audit,
                        KafkaHealthSnapshot.Repository snapshots) {
        this.instances = instances;
        this.gateway = gateway;
        this.audit = audit;
        this.snapshots = snapshots;
    }

    public record InstanceCard(KafkaInstance instance, KafkaHealthSnapshot lastHealth) {
    }

    public List<InstanceCard> instances() {
        return instances.all().stream()
                .map(i -> new InstanceCard(i, snapshots.findFirstByInstanceIdOrderByTsDesc(i.id()).orElse(null)))
                .toList();
    }

    public ClusterOverview overview(Long id) {
        return gateway.overview(instances.get(id));
    }

    public List<KafkaHealthSnapshot> history(Long id, int hours) {
        return snapshots.findByInstanceIdAndTsAfterOrderByTsAsc(id, Instant.now().minus(hours, ChronoUnit.HOURS));
    }

    public List<BrokerInfo> brokers(Long id) {
        return gateway.brokers(instances.get(id));
    }

    // ------------------------------------------------------------ topics

    public List<TopicSummary> topics(Long id) {
        return gateway.topics(instances.get(id));
    }

    public TopicDetail topic(Long id, String topic) {
        return gateway.topic(instances.get(id), topic);
    }

    public TopicDetail createTopic(Long id, CreateTopicRequest request) {
        KafkaInstance instance = instances.get(id);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("partitions", request.partitions());
        details.put("replicationFactor", request.replicationFactor());
        details.put("configs", request.configs() == null ? Map.of() : request.configs());
        audit.track("KAFKA_TOPIC_CREATE", "kafka-topic", target(instance, request.name()), details,
                () -> gateway.createTopic(instance, request));
        return gateway.topic(instance, request.name());
    }

    public TopicDetail alterTopicConfigs(Long id, String topic, AlterConfigsRequest request) {
        KafkaInstance instance = instances.get(id);
        TopicDetail before = gateway.topic(instance, topic);
        Map<String, Object> previous = new LinkedHashMap<>();
        if (request.set() != null) {
            request.set().keySet().forEach(k -> previous.put(k, before.configs().stream()
                    .filter(c -> c.name().equals(k)).map(KafkaModels.ConfigEntry::value).findFirst().orElse(null)));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("before", previous);
        details.put("after", request.set() == null ? Map.of() : request.set());
        details.put("reset", request.delete() == null ? List.of() : request.delete());
        audit.track("KAFKA_TOPIC_CONFIG", "kafka-topic", target(instance, topic), details,
                () -> gateway.alterTopicConfigs(instance, topic, request.set(), request.delete()));
        return gateway.topic(instance, topic);
    }

    public TopicDetail addPartitions(Long id, String topic, int totalCount) {
        KafkaInstance instance = instances.get(id);
        int current = gateway.topic(instance, topic).partitions().size();
        if (totalCount <= current) {
            throw ApiException.badRequest("Topic has " + current + " partitions; partitions can only be increased");
        }
        audit.track("KAFKA_TOPIC_PARTITIONS", "kafka-topic", target(instance, topic), Map.of("before", current, "after", totalCount),
                () -> gateway.addPartitions(instance, topic, totalCount));
        return gateway.topic(instance, topic);
    }

    public void purgeTopic(Long id, String topic, Map<Integer, Long> beforeOffsets) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_TOPIC_PURGE", "kafka-topic", target(instance, topic),
                Map.of("beforeOffsets", beforeOffsets == null || beforeOffsets.isEmpty() ? "ALL" : beforeOffsets),
                () -> gateway.purgeTopic(instance, topic, beforeOffsets));
    }

    public void deleteTopic(Long id, String topic) {
        KafkaInstance instance = instances.get(id);
        if (topic.startsWith("__")) {
            throw ApiException.badRequest("Internal topics cannot be deleted");
        }
        audit.track("KAFKA_TOPIC_DELETE", "kafka-topic", target(instance, topic), Map.of(), () -> gateway.deleteTopic(instance, topic));
    }

    // ------------------------------------------------------------ consumer groups

    public List<ConsumerGroupSummary> consumerGroups(Long id) {
        return gateway.consumerGroups(instances.get(id));
    }

    public ConsumerGroupDetail consumerGroup(Long id, String group) {
        return gateway.consumerGroup(instances.get(id), group);
    }

    public ConsumerGroupDetail resetOffsets(Long id, String group, ResetOffsetsRequest request) {
        KafkaInstance instance = instances.get(id);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("topic", request.topic());
        details.put("strategy", request.strategy().name());
        details.put("value", request.value());
        audit.track("KAFKA_GROUP_RESET_OFFSETS", "kafka-group", target(instance, group), details,
                () -> gateway.resetOffsets(instance, group, request));
        return gateway.consumerGroup(instance, group);
    }

    public void deleteConsumerGroup(Long id, String group) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_GROUP_DELETE", "kafka-group", target(instance, group), Map.of(),
                () -> gateway.deleteConsumerGroup(instance, group));
    }

    // ------------------------------------------------------------ connect

    public List<Connector> connectors(Long id) {
        return gateway.connectors(instances.get(id));
    }

    public Connector connector(Long id, String name) {
        return gateway.connector(instances.get(id), name);
    }

    public Connector createConnector(Long id, String name, Map<String, String> config) {
        KafkaInstance instance = instances.get(id);
        return audit.track("KAFKA_CONNECTOR_CREATE", "kafka-connector", target(instance, name), Map.of("after", config),
                () -> gateway.createConnector(instance, name, config));
    }

    public Connector updateConnectorConfig(Long id, String name, Map<String, String> config) {
        KafkaInstance instance = instances.get(id);
        Map<String, String> before = gateway.connector(instance, name).config();
        return audit.track("KAFKA_CONNECTOR_UPDATE", "kafka-connector", target(instance, name), Map.of("before", before, "after", config),
                () -> gateway.updateConnectorConfig(instance, name, config));
    }

    public void deleteConnector(Long id, String name) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_CONNECTOR_DELETE", "kafka-connector", target(instance, name), Map.of(),
                () -> gateway.deleteConnector(instance, name));
    }

    public Connector restartConnector(Long id, String name, boolean includeTasks, boolean onlyFailed) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_CONNECTOR_RESTART", "kafka-connector", target(instance, name),
                Map.of("includeTasks", includeTasks, "onlyFailed", onlyFailed),
                () -> gateway.restartConnector(instance, name, includeTasks, onlyFailed));
        return gateway.connector(instance, name);
    }

    public Connector restartTask(Long id, String name, int task) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_TASK_RESTART", "kafka-connector", target(instance, name), Map.of("task", task),
                () -> gateway.restartTask(instance, name, task));
        return gateway.connector(instance, name);
    }

    public Connector pause(Long id, String name) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_CONNECTOR_PAUSE", "kafka-connector", target(instance, name), Map.of(),
                () -> gateway.pauseConnector(instance, name));
        return gateway.connector(instance, name);
    }

    public Connector resume(Long id, String name) {
        KafkaInstance instance = instances.get(id);
        audit.track("KAFKA_CONNECTOR_RESUME", "kafka-connector", target(instance, name), Map.of(),
                () -> gateway.resumeConnector(instance, name));
        return gateway.connector(instance, name);
    }

    public List<Map<String, Object>> plugins(Long id) {
        return gateway.connectorPlugins(instances.get(id));
    }

    /** Pass-through to the Kafka Connect REST API, limited to Connect resources. */
    public RawResponse raw(Long id, String method, String path, Object body) {
        String m = method.toUpperCase();
        if (!RAW_METHODS.contains(m)) {
            throw ApiException.badRequest("Method must be one of " + RAW_METHODS);
        }
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.contains("..") || !(p.equals("/") || p.startsWith("/connectors") || p.startsWith("/connector-plugins")
                || p.startsWith("/admin/loggers"))) {
            throw ApiException.badRequest("Only /, /connectors, /connector-plugins and /admin/loggers paths are allowed");
        }
        KafkaInstance instance = instances.get(id);
        if (m.equals("GET")) {
            return gateway.connectRaw(instance, m, p, null);
        }
        return audit.track("KAFKA_CONNECT_RAW", "kafka-connect", instance.name() + " " + m + " " + p,
                body == null ? Map.of() : Map.of("body", body), () -> gateway.connectRaw(instance, m, p, body));
    }

    private static String target(KafkaInstance instance, String name) {
        return instance.name() + "/" + name;
    }
}
