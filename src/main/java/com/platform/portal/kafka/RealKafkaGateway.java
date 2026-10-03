package com.platform.portal.kafka;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.platform.portal.common.ApiException;
import com.platform.portal.common.Json;
import com.platform.portal.common.Strings;
import com.platform.portal.kafka.KafkaModels.BrokerInfo;
import com.platform.portal.kafka.KafkaModels.ClusterOverview;
import com.platform.portal.kafka.KafkaModels.ConfigEntry;
import com.platform.portal.kafka.KafkaModels.Connector;
import com.platform.portal.kafka.KafkaModels.ConnectorTask;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupDetail;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupSummary;
import com.platform.portal.kafka.KafkaModels.CreateTopicRequest;
import com.platform.portal.kafka.KafkaModels.GroupMember;
import com.platform.portal.kafka.KafkaModels.KafkaInstance;
import com.platform.portal.kafka.KafkaModels.OffsetLag;
import com.platform.portal.kafka.KafkaModels.PartitionInfo;
import com.platform.portal.kafka.KafkaModels.RawResponse;
import com.platform.portal.kafka.KafkaModels.ResetOffsetsRequest;
import com.platform.portal.kafka.KafkaModels.TopicDetail;
import com.platform.portal.kafka.KafkaModels.TopicSummary;
import com.platform.portal.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Talks to real clusters through the Kafka Admin API and to Kafka Connect workers over REST. */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "real", matchIfMissing = true)
public class RealKafkaGateway implements KafkaGateway {

    private static final Logger log = LoggerFactory.getLogger(RealKafkaGateway.class);
    private static final long TIMEOUT_SECONDS = 20;
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };

    private final SettingsService settings;
    private final Json json;
    private final Map<Long, CachedAdmin> admins = new ConcurrentHashMap<>();
    private final RestClient http;

    public RealKafkaGateway(SettingsService settings, Json json) {
        this.settings = settings;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    private record CachedAdmin(String fingerprint, Admin admin) {
    }

    // ------------------------------------------------------------------ cluster

    @Override
    public ClusterOverview overview(KafkaInstance instance) {
        long start = System.nanoTime();
        try {
            Admin admin = admin(instance);
            DescribeClusterResult cluster = admin.describeCluster();
            Collection<Node> nodes = await(cluster.nodes());
            Node controller = await(cluster.controller());
            String clusterId = await(cluster.clusterId());
            Set<String> names = await(admin.listTopics().names());
            Map<String, TopicDescription> topics = names.isEmpty() ? Map.of() : await(admin.describeTopics(names).allTopicNames());
            int partitions = 0;
            int urp = 0;
            int offline = 0;
            for (TopicDescription t : topics.values()) {
                for (TopicPartitionInfo p : t.partitions()) {
                    partitions++;
                    if (p.leader() == null || p.leader().isEmpty()) offline++;
                    if (p.isr().size() < p.replicas().size()) urp++;
                }
            }
            int groups = await(admin.listGroups(ListGroupsOptions.forConsumerGroups()).all()).size();
            int connectorsTotal = 0;
            int connectorsFailed = 0;
            String connectVersion = null;
            String error = null;
            if (!instance.connectUrls().isEmpty()) {
                try {
                    List<Connector> connectors = connectors(instance, false);
                    connectorsTotal = connectors.size();
                    connectorsFailed = (int) connectors.stream().filter(RealKafkaGateway::isFailed).count();
                    Map<String, Object> root = connectCall(instance, HttpMethod.GET, "/", null, MAP);
                    connectVersion = root == null ? null : String.valueOf(root.get("version"));
                } catch (RuntimeException e) {
                    error = "Kafka Connect unreachable: " + e.getMessage();
                }
            }
            int expected = Math.max(instance.brokers().size(), nodes.size());
            String status = offline > 0 || nodes.isEmpty() ? "DOWN"
                    : (nodes.size() < expected || urp > 0 || connectorsFailed > 0 || error != null) ? "DEGRADED" : "HEALTHY";
            return new ClusterOverview(instance, status, clusterId, controller == null ? null : controller.id(), nodes.size(), expected,
                    names.size(), partitions, urp, offline, groups, connectorsTotal, connectorsFailed, connectVersion, error,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            return new ClusterOverview(instance, "UNREACHABLE", null, null, 0, instance.brokers().size(), 0, 0, 0, 0, 0, 0, 0,
                    null, e.getMessage(), (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Override
    public List<BrokerInfo> brokers(KafkaInstance instance) {
        Admin admin = admin(instance);
        DescribeClusterResult cluster = admin.describeCluster();
        Node controller = await(cluster.controller());
        return await(cluster.nodes()).stream()
                .map(n -> new BrokerInfo(n.id(), n.host(), n.port(), n.rack(), controller != null && controller.id() == n.id()))
                .sorted(Comparator.comparingInt(BrokerInfo::id))
                .toList();
    }

    // ------------------------------------------------------------------ topics

    @Override
    public List<TopicSummary> topics(KafkaInstance instance) {
        Admin admin = admin(instance);
        Set<String> names = await(admin.listTopics(new org.apache.kafka.clients.admin.ListTopicsOptions().listInternal(true)).names());
        if (names.isEmpty()) {
            return List.of();
        }
        Map<String, TopicDescription> descriptions = await(admin.describeTopics(names).allTopicNames());
        Map<ConfigResource, Config> configs = await(admin.describeConfigs(names.stream()
                .map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList()).all());
        return descriptions.values().stream().map(t -> {
            Config cfg = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, t.name()));
            int rf = t.partitions().isEmpty() ? 0 : t.partitions().getFirst().replicas().size();
            int urp = (int) t.partitions().stream().filter(p -> p.isr().size() < p.replicas().size()).count();
            return new TopicSummary(t.name(), t.partitions().size(), rf, t.isInternal(), urp,
                    value(cfg, "retention.ms"), value(cfg, "cleanup.policy"));
        }).sorted(Comparator.comparing(TopicSummary::name)).toList();
    }

    @Override
    public TopicDetail topic(KafkaInstance instance, String topic) {
        Admin admin = admin(instance);
        TopicDescription description = await(admin.describeTopics(List.of(topic)).allTopicNames()).get(topic);
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = await(admin.describeConfigs(List.of(resource)).all()).get(resource);
        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        description.partitions().forEach(p -> {
            earliest.put(new TopicPartition(topic, p.partition()), OffsetSpec.earliest());
            latest.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest());
        });
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> early = await(admin.listOffsets(earliest).all());
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> late = await(admin.listOffsets(latest).all());
        long messages = 0;
        List<PartitionInfo> partitions = new ArrayList<>();
        for (TopicPartitionInfo p : description.partitions()) {
            TopicPartition tp = new TopicPartition(topic, p.partition());
            Long e = early.containsKey(tp) ? early.get(tp).offset() : null;
            Long l = late.containsKey(tp) ? late.get(tp).offset() : null;
            if (e != null && l != null) messages += Math.max(0, l - e);
            partitions.add(new PartitionInfo(p.partition(), p.leader() == null ? null : p.leader().id(),
                    p.replicas().stream().map(Node::id).toList(), p.isr().stream().map(Node::id).toList(), e, l));
        }
        List<ConfigEntry> entries = config.entries().stream()
                .map(c -> new ConfigEntry(c.name(), c.isSensitive() ? null : c.value(), c.source().name(), c.isDefault(), c.isReadOnly(), c.isSensitive()))
                .sorted(Comparator.comparing(ConfigEntry::name))
                .toList();
        return new TopicDetail(topic, description.isInternal(), partitions, entries, messages);
    }

    @Override
    public void createTopic(KafkaInstance instance, CreateTopicRequest request) {
        NewTopic topic = new NewTopic(request.name(), request.partitions(), request.replicationFactor());
        if (request.configs() != null && !request.configs().isEmpty()) {
            topic.configs(request.configs());
        }
        await(admin(instance).createTopics(List.of(topic)).all());
    }

    @Override
    public void alterTopicConfigs(KafkaInstance instance, String topic, Map<String, String> set, List<String> delete) {
        List<AlterConfigOp> ops = new ArrayList<>();
        if (set != null) {
            set.forEach((k, v) -> ops.add(new AlterConfigOp(new org.apache.kafka.clients.admin.ConfigEntry(k, v), AlterConfigOp.OpType.SET)));
        }
        if (delete != null) {
            delete.forEach(k -> ops.add(new AlterConfigOp(new org.apache.kafka.clients.admin.ConfigEntry(k, null), AlterConfigOp.OpType.DELETE)));
        }
        if (ops.isEmpty()) {
            return;
        }
        await(admin(instance).incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.TOPIC, topic), ops)).all());
    }

    @Override
    public void addPartitions(KafkaInstance instance, String topic, int totalCount) {
        await(admin(instance).createPartitions(Map.of(topic, NewPartitions.increaseTo(totalCount))).all());
    }

    @Override
    public void deleteTopic(KafkaInstance instance, String topic) {
        await(admin(instance).deleteTopics(List.of(topic)).all());
    }

    @Override
    public void purgeTopic(KafkaInstance instance, String topic, Map<Integer, Long> beforeOffsets) {
        Admin admin = admin(instance);
        Map<TopicPartition, RecordsToDelete> toDelete = new HashMap<>();
        if (beforeOffsets == null || beforeOffsets.isEmpty()) {
            TopicDescription d = await(admin.describeTopics(List.of(topic)).allTopicNames()).get(topic);
            Map<TopicPartition, OffsetSpec> latest = d.partitions().stream()
                    .collect(Collectors.toMap(p -> new TopicPartition(topic, p.partition()), p -> OffsetSpec.latest()));
            await(admin.listOffsets(latest).all()).forEach((tp, info) -> toDelete.put(tp, RecordsToDelete.beforeOffset(info.offset())));
        } else {
            beforeOffsets.forEach((p, o) -> toDelete.put(new TopicPartition(topic, p), RecordsToDelete.beforeOffset(o)));
        }
        await(admin.deleteRecords(toDelete).all());
    }

    // ------------------------------------------------------------------ consumer groups

    @Override
    public List<ConsumerGroupSummary> consumerGroups(KafkaInstance instance) {
        Admin admin = admin(instance);
        List<String> ids = await(admin.listGroups(ListGroupsOptions.forConsumerGroups()).all()).stream()
                .map(GroupListing::groupId).sorted().limit(500).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, ConsumerGroupDescription> descriptions = await(admin.describeConsumerGroups(ids).all());
        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new HashMap<>();
        for (String id : ids) {
            committed.put(id, await(admin.listConsumerGroupOffsets(id).partitionsToOffsetAndMetadata()));
        }
        Map<TopicPartition, Long> ends = endOffsets(admin, committed.values().stream()
                .flatMap(m -> m.keySet().stream()).collect(Collectors.toSet()));
        return ids.stream().map(id -> {
            ConsumerGroupDescription d = descriptions.get(id);
            Map<TopicPartition, OffsetAndMetadata> offsets = committed.getOrDefault(id, Map.of());
            long lag = offsets.entrySet().stream()
                    .filter(e -> e.getValue() != null && ends.containsKey(e.getKey()))
                    .mapToLong(e -> Math.max(0, ends.get(e.getKey()) - e.getValue().offset())).sum();
            List<String> topics = offsets.keySet().stream().map(TopicPartition::topic).distinct().sorted().toList();
            return new ConsumerGroupSummary(id, d == null ? "UNKNOWN" : d.groupState().name(), d == null ? 0 : d.members().size(), topics, lag);
        }).toList();
    }

    @Override
    public ConsumerGroupDetail consumerGroup(KafkaInstance instance, String groupId) {
        Admin admin = admin(instance);
        ConsumerGroupDescription d = await(admin.describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        Map<TopicPartition, OffsetAndMetadata> offsets = await(admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata());
        Map<TopicPartition, Long> ends = endOffsets(admin, offsets.keySet());
        List<OffsetLag> lags = offsets.entrySet().stream().map(e -> {
            Long committed = e.getValue() == null ? null : e.getValue().offset();
            Long end = ends.get(e.getKey());
            Long lag = committed == null || end == null ? null : Math.max(0, end - committed);
            return new OffsetLag(e.getKey().topic(), e.getKey().partition(), committed, end, lag);
        }).sorted(Comparator.comparing(OffsetLag::topic).thenComparingInt(OffsetLag::partition)).toList();
        List<GroupMember> members = d.members().stream().map(m -> new GroupMember(m.consumerId(), m.clientId(), m.host(),
                m.assignment().topicPartitions().stream().map(tp -> tp.topic() + "-" + tp.partition()).sorted().toList())).toList();
        long total = lags.stream().mapToLong(l -> l.lag() == null ? 0 : l.lag()).sum();
        return new ConsumerGroupDetail(groupId, d.groupState().name(), d.coordinator() == null ? null : d.coordinator().id(),
                members, lags, total);
    }

    @Override
    public void resetOffsets(KafkaInstance instance, String groupId, ResetOffsetsRequest request) {
        Admin admin = admin(instance);
        ConsumerGroupDescription d = await(admin.describeConsumerGroups(List.of(groupId)).all()).get(groupId);
        if (!d.members().isEmpty()) {
            throw ApiException.conflict("Consumer group " + groupId + " has active members - stop consumers before resetting offsets");
        }
        TopicDescription topic = await(admin.describeTopics(List.of(request.topic())).allTopicNames()).get(request.topic());
        Map<TopicPartition, OffsetAndMetadata> target = new HashMap<>();
        if (request.strategy() == ResetOffsetsRequest.Strategy.OFFSET) {
            if (request.value() == null) throw ApiException.badRequest("value (offset) is required");
            topic.partitions().forEach(p -> target.put(new TopicPartition(request.topic(), p.partition()), new OffsetAndMetadata(request.value())));
        } else {
            OffsetSpec spec = switch (request.strategy()) {
                case EARLIEST -> OffsetSpec.earliest();
                case LATEST -> OffsetSpec.latest();
                case TIMESTAMP -> {
                    if (request.value() == null) throw ApiException.badRequest("value (epoch millis) is required");
                    yield OffsetSpec.forTimestamp(request.value());
                }
                default -> throw new IllegalStateException();
            };
            Map<TopicPartition, OffsetSpec> specs = topic.partitions().stream()
                    .collect(Collectors.toMap(p -> new TopicPartition(request.topic(), p.partition()), p -> spec));
            await(admin.listOffsets(specs).all()).forEach((tp, info) ->
                    target.put(tp, new OffsetAndMetadata(info.offset() < 0 ? 0 : info.offset())));
        }
        await(admin.alterConsumerGroupOffsets(groupId, target).all());
    }

    @Override
    public void deleteConsumerGroup(KafkaInstance instance, String groupId) {
        await(admin(instance).deleteConsumerGroups(List.of(groupId)).all());
    }

    private Map<TopicPartition, Long> endOffsets(Admin admin, Set<TopicPartition> partitions) {
        if (partitions.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> specs = partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
        Map<TopicPartition, Long> ends = new HashMap<>();
        try {
            await(admin.listOffsets(specs).all()).forEach((tp, info) -> ends.put(tp, info.offset()));
        } catch (ApiException e) {
            log.debug("Could not list end offsets: {}", e.getMessage());
        }
        return ends;
    }

    // ------------------------------------------------------------------ Kafka Connect

    @Override
    public List<Connector> connectors(KafkaInstance instance) {
        return connectors(instance, true);
    }

    @SuppressWarnings("unchecked")
    private List<Connector> connectors(KafkaInstance instance, boolean withLag) {
        Map<String, Object> expanded = connectCall(instance, HttpMethod.GET, "/connectors?expand=status&expand=info", null, MAP);
        if (expanded == null) {
            return List.of();
        }
        Map<String, Long> lagByGroup = new HashMap<>();
        List<Connector> result = new ArrayList<>();
        for (Map.Entry<String, Object> e : expanded.entrySet()) {
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            result.add(toConnector(e.getKey(), (Map<String, Object>) entry.get("status"), (Map<String, Object>) entry.get("info"), null));
        }
        if (withLag && result.stream().anyMatch(c -> "sink".equals(c.type()))) {
            try {
                Admin admin = admin(instance);
                for (Connector c : result) {
                    if (!"sink".equals(c.type())) continue;
                    String group = c.config().getOrDefault("consumer.override.group.id", "connect-" + c.name());
                    Map<TopicPartition, OffsetAndMetadata> offsets = await(admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata());
                    Map<TopicPartition, Long> ends = endOffsets(admin, offsets.keySet());
                    lagByGroup.put(c.name(), offsets.entrySet().stream().filter(o -> o.getValue() != null && ends.containsKey(o.getKey()))
                            .mapToLong(o -> Math.max(0, ends.get(o.getKey()) - o.getValue().offset())).sum());
                }
            } catch (RuntimeException ex) {
                log.debug("Sink lag lookup failed for {}: {}", instance.name(), ex.getMessage());
            }
            return result.stream().map(c -> new Connector(c.name(), c.type(), c.state(), c.workerId(), c.tasks(), c.config(),
                    lagByGroup.get(c.name()))).sorted(Comparator.comparing(Connector::name)).toList();
        }
        return result.stream().sorted(Comparator.comparing(Connector::name)).toList();
    }

    @Override
    public Connector connector(KafkaInstance instance, String name) {
        Map<String, Object> status = connectCall(instance, HttpMethod.GET, "/connectors/" + enc(name) + "/status", null, MAP);
        Map<String, Object> info = connectCall(instance, HttpMethod.GET, "/connectors/" + enc(name), null, MAP);
        return toConnector(name, status, info, null);
    }

    @Override
    public Connector createConnector(KafkaInstance instance, String name, Map<String, String> config) {
        connectCall(instance, HttpMethod.POST, "/connectors", Map.of("name", name, "config", config), MAP);
        return connector(instance, name);
    }

    @Override
    public Connector updateConnectorConfig(KafkaInstance instance, String name, Map<String, String> config) {
        connectCall(instance, HttpMethod.PUT, "/connectors/" + enc(name) + "/config", config, MAP);
        return connector(instance, name);
    }

    @Override
    public void deleteConnector(KafkaInstance instance, String name) {
        connectCall(instance, HttpMethod.DELETE, "/connectors/" + enc(name), null, MAP);
    }

    @Override
    public void restartConnector(KafkaInstance instance, String name, boolean includeTasks, boolean onlyFailed) {
        connectCall(instance, HttpMethod.POST, "/connectors/" + enc(name) + "/restart?includeTasks=" + includeTasks + "&onlyFailed=" + onlyFailed, null, MAP);
    }

    @Override
    public void restartTask(KafkaInstance instance, String name, int taskId) {
        connectCall(instance, HttpMethod.POST, "/connectors/" + enc(name) + "/tasks/" + taskId + "/restart", null, MAP);
    }

    @Override
    public void pauseConnector(KafkaInstance instance, String name) {
        connectCall(instance, HttpMethod.PUT, "/connectors/" + enc(name) + "/pause", null, MAP);
    }

    @Override
    public void resumeConnector(KafkaInstance instance, String name) {
        connectCall(instance, HttpMethod.PUT, "/connectors/" + enc(name) + "/resume", null, MAP);
    }

    @Override
    public List<Map<String, Object>> connectorPlugins(KafkaInstance instance) {
        List<Map<String, Object>> plugins = connectCall(instance, HttpMethod.GET, "/connector-plugins", null, LIST);
        return plugins == null ? List.of() : plugins;
    }

    @Override
    public RawResponse connectRaw(KafkaInstance instance, String method, String path, Object body) {
        requireConnect(instance);
        long start = System.nanoTime();
        RuntimeException last = null;
        for (String base : instance.connectUrls()) {
            try {
                RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(method.toUpperCase())).uri(base + path)
                        .accept(MediaType.APPLICATION_JSON);
                if (body != null) {
                    spec.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                String url = base + path;
                return spec.exchange((req, res) -> {
                    String text = new String(res.getBody().readAllBytes());
                    Object parsed = text;
                    try {
                        parsed = text.isBlank() ? null : json.read(text);
                    } catch (RuntimeException ignored) {
                        // not JSON - return raw text
                    }
                    return new RawResponse(res.getStatusCode().value(), parsed, url, (System.nanoTime() - start) / 1_000_000);
                });
            } catch (ResourceAccessException e) {
                last = e;
            }
        }
        throw ApiException.upstream("No Kafka Connect worker reachable for " + instance.name(), last);
    }

    // ------------------------------------------------------------------ helpers

    private <T> T connectCall(KafkaInstance instance, HttpMethod method, String path, Object body, ParameterizedTypeReference<T> type) {
        requireConnect(instance);
        ResourceAccessException last = null;
        for (String base : instance.connectUrls()) {
            try {
                RestClient.RequestBodySpec spec = http.method(method).uri(base + path).accept(MediaType.APPLICATION_JSON);
                if (body != null) {
                    spec.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                return spec.retrieve().body(type);
            } catch (ResourceAccessException e) {
                last = e; // try next worker
            } catch (RestClientResponseException e) {
                String msg = e.getResponseBodyAsString();
                throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Kafka Connect returned " + e.getStatusCode().value() + ": " + Strings.truncate(msg, 400), e);
            }
        }
        throw ApiException.upstream("No Kafka Connect worker reachable for " + instance.name() + " ("
                + String.join(", ", instance.connectUrls()) + ")", last);
    }

    private static void requireConnect(KafkaInstance instance) {
        if (instance.connectUrls().isEmpty()) {
            throw ApiException.badRequest("No Connect / sink IPs configured for '" + instance.name() + "' in Inventory");
        }
    }

    @SuppressWarnings("unchecked")
    private static Connector toConnector(String name, Map<String, Object> status, Map<String, Object> info, Long lag) {
        Map<String, Object> connector = status == null ? Map.of() : (Map<String, Object>) status.getOrDefault("connector", Map.of());
        List<Map<String, Object>> tasks = status == null ? List.of() : (List<Map<String, Object>>) status.getOrDefault("tasks", List.of());
        Map<String, String> config = new LinkedHashMap<>();
        if (info != null && info.get("config") instanceof Map<?, ?> cfg) {
            cfg.forEach((k, v) -> config.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        String type = status != null && status.get("type") != null ? String.valueOf(status.get("type"))
                : info != null && info.get("type") != null ? String.valueOf(info.get("type")) : "unknown";
        return new Connector(name, type, String.valueOf(connector.getOrDefault("state", "UNKNOWN")),
                (String) connector.get("worker_id"),
                tasks.stream().map(t -> new ConnectorTask(((Number) t.get("id")).intValue(), String.valueOf(t.get("state")),
                        (String) t.get("worker_id"), (String) t.get("trace"))).toList(),
                config, lag);
    }

    static boolean isFailed(Connector c) {
        return "FAILED".equals(c.state()) || c.tasks().stream().anyMatch(t -> "FAILED".equals(t.state()));
    }

    private Admin admin(KafkaInstance instance) {
        Properties props = adminProperties(instance);
        String fingerprint = props.toString();
        CachedAdmin cached = admins.get(instance.id());
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.admin();
        }
        synchronized (admins) {
            cached = admins.get(instance.id());
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                return cached.admin();
            }
            if (cached != null) {
                closeQuietly(cached.admin());
            }
            Admin admin = Admin.create(props);
            admins.put(instance.id(), new CachedAdmin(fingerprint, admin));
            return admin;
        }
    }

    private Properties adminProperties(KafkaInstance instance) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", instance.brokers()));
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, "devops-portal");
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000");
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000");
        p.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, instance.securityProtocol());
        Map<String, String> cred = instance.credentialRef() == null ? Map.of() : settings.resolveKey(instance.credentialRef());
        if (instance.securityProtocol().startsWith("SASL")) {
            String mechanism = Strings.isBlank(instance.saslMechanism()) ? "PLAIN" : instance.saslMechanism();
            String module = mechanism.startsWith("SCRAM") ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule";
            p.put("sasl.mechanism", mechanism);
            p.put("sasl.jaas.config", "%s required username=\"%s\" password=\"%s\";".formatted(module,
                    jaasEscape(cred.getOrDefault("username", "")), jaasEscape(cred.getOrDefault("password", ""))));
        }
        if (!Strings.isBlank(cred.get("truststoreLocation"))) {
            p.put("ssl.truststore.location", cred.get("truststoreLocation"));
            if (!Strings.isBlank(cred.get("truststorePassword"))) {
                p.put("ssl.truststore.password", cred.get("truststorePassword"));
            }
        }
        return p;
    }

    private static String jaasEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void evict(Long instanceId) {
        CachedAdmin cached = admins.remove(instanceId);
        if (cached != null) {
            closeQuietly(cached.admin());
        }
    }

    @PreDestroy
    void shutdown() {
        admins.values().forEach(c -> closeQuietly(c.admin()));
        admins.clear();
    }

    private static void closeQuietly(Admin admin) {
        try {
            admin.close(Duration.ofSeconds(2));
        } catch (RuntimeException e) {
            log.debug("Error closing Kafka admin client", e);
        }
    }

    private static String value(Config config, String name) {
        if (config == null || config.get(name) == null) return null;
        return config.get(name).value();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream("Interrupted while waiting for Kafka", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw ApiException.upstream("Kafka: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw ApiException.upstream("Kafka did not respond within " + TIMEOUT_SECONDS + "s", e);
        }
    }
}
