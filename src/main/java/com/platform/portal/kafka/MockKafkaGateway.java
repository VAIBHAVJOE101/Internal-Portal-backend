package com.platform.portal.kafka;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.platform.portal.common.ApiException;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * In-memory Kafka + Kafka Connect simulation used in {@code mock} mode. State is mutable, so topic
 * changes, connector restarts and offset resets behave like the real thing for demos and UI work.
 */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "mock")
public class MockKafkaGateway implements KafkaGateway {

    private static final Map<String, String> TOPIC_DEFAULTS = new TreeMap<>(Map.ofEntries(
            Map.entry("cleanup.policy", "delete"),
            Map.entry("compression.type", "producer"),
            Map.entry("delete.retention.ms", "86400000"),
            Map.entry("max.message.bytes", "1048588"),
            Map.entry("message.timestamp.type", "CreateTime"),
            Map.entry("min.insync.replicas", "1"),
            Map.entry("retention.bytes", "-1"),
            Map.entry("retention.ms", "604800000"),
            Map.entry("segment.bytes", "1073741824"),
            Map.entry("segment.ms", "604800000"),
            Map.entry("unclean.leader.election.enable", "false")));

    private final Map<Long, Cluster> clusters = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ model

    private static final class Topic {
        final String name;
        final short rf;
        final boolean internal;
        final Map<String, String> overrides = new TreeMap<>();
        long[] earliest;
        long[] latest;

        Topic(String name, int partitions, short rf, boolean internal, Random random) {
            this.name = name;
            this.rf = rf;
            this.internal = internal;
            this.earliest = new long[partitions];
            this.latest = new long[partitions];
            for (int i = 0; i < partitions; i++) {
                latest[i] = internal ? 50 + random.nextInt(100) : 20_000 + random.nextInt(400_000);
            }
        }

        int partitions() {
            return latest.length;
        }
    }

    private static final class Group {
        final String id;
        int members;
        final Map<String, long[]> committed = new TreeMap<>();

        Group(String id, int members) {
            this.id = id;
            this.members = members;
        }
    }

    private static final class ConnectorState {
        final String name;
        final String type;
        String state = "RUNNING";
        final Map<String, String> config;
        final List<String> taskStates = new ArrayList<>();
        String trace;

        ConnectorState(String name, String type, Map<String, String> config, int tasks) {
            this.name = name;
            this.type = type;
            this.config = new LinkedHashMap<>(config);
            for (int i = 0; i < tasks; i++) taskStates.add("RUNNING");
        }
    }

    private static final class Cluster {
        final String clusterId;
        final List<BrokerInfo> brokers = new ArrayList<>();
        final Map<String, Topic> topics = new TreeMap<>();
        final Map<String, Group> groups = new TreeMap<>();
        final Map<String, ConnectorState> connectors = new TreeMap<>();
        final Random random;

        Cluster(String clusterId, Random random) {
            this.clusterId = clusterId;
            this.random = random;
        }
    }

    private synchronized Cluster cluster(KafkaInstance instance) {
        return clusters.computeIfAbsent(instance.id(), id -> seed(instance));
    }

    private Cluster seed(KafkaInstance instance) {
        Random random = new Random(instance.name().hashCode());
        Cluster c = new Cluster("mock-" + Integer.toHexString(Math.abs(instance.name().hashCode())), random);
        int i = 1;
        for (String b : instance.brokers()) {
            String[] hp = b.split(":");
            c.brokers.add(new BrokerInfo(i, hp[0], hp.length > 1 ? Integer.parseInt(hp[1].replaceAll("\\D", "")) : 9092,
                    "zone-" + ((i - 1) % 3 + 1), i == 1));
            i++;
        }
        short rf = (short) Math.min(3, c.brokers.size());
        String env = instance.environment() == null ? "dev" : instance.environment();
        for (String name : List.of("orders.created", "orders.updated", "payments.authorized", "payments.settled",
                "customers.profile", "inventory.stock-level", "notifications.email", "audit.events", "fraud.alerts",
                "gateway.requests." + env, "dlq.orders-sink", "cdc.orders.public.orders")) {
            c.topics.put(name, new Topic(name, 3 + random.nextInt(10), rf, false, random));
        }
        c.topics.put("__consumer_offsets", new Topic("__consumer_offsets", 50, rf, true, random));
        c.topics.put("connect-configs", new Topic("connect-configs", 1, rf, true, random));
        c.topics.put("connect-offsets", new Topic("connect-offsets", 25, rf, true, random));
        c.topics.put("connect-status", new Topic("connect-status", 5, rf, true, random));
        c.topics.get("audit.events").overrides.put("retention.ms", "2592000000");
        c.topics.get("customers.profile").overrides.put("cleanup.policy", "compact");
        c.topics.get("payments.settled").overrides.put("min.insync.replicas", "2");

        connector(c, "orders-elastic-sink", "sink", "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
                "orders.created,orders.updated", 3);
        connector(c, "payments-jdbc-sink", "sink", "io.confluent.connect.jdbc.JdbcSinkConnector", "payments.settled", 2);
        connector(c, "audit-s3-sink", "sink", "io.confluent.connect.s3.S3SinkConnector", "audit.events", 2);
        connector(c, "orders-cdc-source", "source", "io.debezium.connector.postgresql.PostgresConnector", null, 1);

        if ("dev".equals(env)) {
            ConnectorState failed = c.connectors.get("audit-s3-sink");
            failed.taskStates.set(1, "FAILED");
            failed.trace = "org.apache.kafka.connect.errors.ConnectException: Unable to write to bucket 'acme-audit-dev': AccessDenied";
        }
        if ("uat".equals(env)) {
            c.connectors.get("payments-jdbc-sink").state = "PAUSED";
            c.connectors.get("payments-jdbc-sink").taskStates.replaceAll(s -> "PAUSED");
        }

        group(c, "orders-service", 3, "orders.created", "payments.authorized");
        group(c, "payments-processor", 2, "payments.authorized");
        group(c, "fraud-detector", 4, "payments.authorized", "orders.created");
        group(c, "notification-worker", 0, "notifications.email");
        for (ConnectorState s : c.connectors.values()) {
            if ("sink".equals(s.type)) {
                group(c, "connect-" + s.name, s.taskStates.size(), s.config.get("topics").split(","));
            }
        }
        if ("prod".equals(env)) {
            // a consumer falling behind, to demonstrate lag alerts
            Group lagging = c.groups.get("fraud-detector");
            lagging.committed.get("payments.authorized")[0] -= 18_000;
        }
        return c;
    }

    private static void connector(Cluster c, String name, String type, String clazz, String topics, int tasks) {
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("connector.class", clazz);
        cfg.put("tasks.max", String.valueOf(tasks));
        if (topics != null) cfg.put("topics", topics);
        if ("sink".equals(type)) {
            cfg.put("errors.tolerance", "all");
            cfg.put("errors.deadletterqueue.topic.name", "dlq.orders-sink");
        } else {
            cfg.put("database.hostname", "pg-orders.internal");
            cfg.put("topic.prefix", "cdc.orders");
        }
        c.connectors.put(name, new ConnectorState(name, type, cfg, tasks));
    }

    private static void group(Cluster c, String id, int members, String... topics) {
        Group g = new Group(id, members);
        for (String t : topics) {
            Topic topic = c.topics.get(t.trim());
            if (topic == null) continue;
            long[] committed = new long[topic.partitions()];
            for (int p = 0; p < committed.length; p++) {
                committed[p] = Math.max(0, topic.latest[p] - c.random.nextInt(members == 0 ? 4000 : 120));
            }
            g.committed.put(topic.name, committed);
        }
        c.groups.put(id, g);
    }

    /** Simulates traffic: producers append, active consumers mostly keep up. */
    private void tick(Cluster c) {
        for (Topic t : c.topics.values()) {
            if (t.internal) continue;
            for (int p = 0; p < t.latest.length; p++) t.latest[p] += c.random.nextInt(40);
        }
        for (Group g : c.groups.values()) {
            boolean paused = g.id.startsWith("connect-") && isStopped(c.connectors.get(g.id.substring(8)));
            g.committed.forEach((topic, offsets) -> {
                Topic t = c.topics.get(topic);
                if (t == null) return;
                for (int p = 0; p < offsets.length && p < t.latest.length; p++) {
                    if (g.members > 0 && !paused) {
                        long behind = t.latest[p] - offsets[p];
                        offsets[p] += Math.max(0, behind - c.random.nextInt(60)) * (g.id.equals("fraud-detector") ? 0 : 1);
                    }
                }
            });
        }
    }

    private static boolean isStopped(ConnectorState s) {
        return s == null || !"RUNNING".equals(s.state) || s.taskStates.contains("FAILED");
    }

    // ------------------------------------------------------------------ cluster

    @Override
    public synchronized ClusterOverview overview(KafkaInstance instance) {
        Cluster c = cluster(instance);
        tick(c);
        int partitions = c.topics.values().stream().mapToInt(Topic::partitions).sum();
        int failed = (int) c.connectors.values().stream().filter(s -> "FAILED".equals(s.state) || s.taskStates.contains("FAILED")).count();
        String status = failed > 0 ? "DEGRADED" : "HEALTHY";
        return new ClusterOverview(instance, status, c.clusterId, 1, c.brokers.size(), instance.brokers().size(), c.topics.size(),
                partitions, 0, 0, c.groups.size(), instance.connectUrls().isEmpty() ? 0 : c.connectors.size(),
                instance.connectUrls().isEmpty() ? 0 : failed, instance.connectUrls().isEmpty() ? null : "3.9.0-mock", null,
                8 + c.random.nextInt(30));
    }

    @Override
    public List<BrokerInfo> brokers(KafkaInstance instance) {
        return List.copyOf(cluster(instance).brokers);
    }

    // ------------------------------------------------------------------ topics

    @Override
    public synchronized List<TopicSummary> topics(KafkaInstance instance) {
        return cluster(instance).topics.values().stream()
                .map(t -> new TopicSummary(t.name, t.partitions(), t.rf, t.internal, 0,
                        t.overrides.getOrDefault("retention.ms", TOPIC_DEFAULTS.get("retention.ms")),
                        t.overrides.getOrDefault("cleanup.policy", TOPIC_DEFAULTS.get("cleanup.policy"))))
                .toList();
    }

    @Override
    public synchronized TopicDetail topic(KafkaInstance instance, String name) {
        Cluster c = cluster(instance);
        Topic t = topic(c, name);
        int brokers = c.brokers.size();
        List<PartitionInfo> partitions = new ArrayList<>();
        long messages = 0;
        for (int p = 0; p < t.partitions(); p++) {
            List<Integer> replicas = new ArrayList<>();
            for (int r = 0; r < t.rf; r++) replicas.add((p + r) % brokers + 1);
            partitions.add(new PartitionInfo(p, replicas.get(0), replicas, replicas, t.earliest[p], t.latest[p]));
            messages += t.latest[p] - t.earliest[p];
        }
        List<ConfigEntry> configs = new ArrayList<>();
        TOPIC_DEFAULTS.forEach((k, v) -> {
            boolean overridden = t.overrides.containsKey(k);
            configs.add(new ConfigEntry(k, overridden ? t.overrides.get(k) : v,
                    overridden ? "DYNAMIC_TOPIC_CONFIG" : "DEFAULT_CONFIG", !overridden, false, false));
        });
        t.overrides.forEach((k, v) -> {
            if (!TOPIC_DEFAULTS.containsKey(k)) configs.add(new ConfigEntry(k, v, "DYNAMIC_TOPIC_CONFIG", false, false, false));
        });
        configs.sort(Comparator.comparing(ConfigEntry::name));
        return new TopicDetail(t.name, t.internal, partitions, configs, messages);
    }

    @Override
    public synchronized void createTopic(KafkaInstance instance, CreateTopicRequest request) {
        Cluster c = cluster(instance);
        if (c.topics.containsKey(request.name())) {
            throw ApiException.conflict("Topic '" + request.name() + "' already exists");
        }
        if (request.replicationFactor() > c.brokers.size()) {
            throw ApiException.badRequest("Replication factor " + request.replicationFactor() + " is larger than the number of brokers ("
                    + c.brokers.size() + ")");
        }
        Topic t = new Topic(request.name(), request.partitions(), request.replicationFactor(), false, c.random);
        Arrays.fill(t.latest, 0);
        if (request.configs() != null) t.overrides.putAll(request.configs());
        c.topics.put(t.name, t);
    }

    @Override
    public synchronized void alterTopicConfigs(KafkaInstance instance, String name, Map<String, String> set, List<String> delete) {
        Topic t = topic(cluster(instance), name);
        if (set != null) {
            set.forEach((k, v) -> {
                if (k.equals("min.insync.replicas") && Integer.parseInt(v) > t.rf) {
                    throw ApiException.badRequest("min.insync.replicas cannot exceed the replication factor (" + t.rf + ")");
                }
                t.overrides.put(k, v);
            });
        }
        if (delete != null) delete.forEach(t.overrides::remove);
    }

    @Override
    public synchronized void addPartitions(KafkaInstance instance, String name, int totalCount) {
        Topic t = topic(cluster(instance), name);
        if (totalCount <= t.partitions()) {
            throw ApiException.badRequest("Topic currently has " + t.partitions() + " partitions; the new count must be larger");
        }
        t.earliest = Arrays.copyOf(t.earliest, totalCount);
        t.latest = Arrays.copyOf(t.latest, totalCount);
    }

    @Override
    public synchronized void deleteTopic(KafkaInstance instance, String name) {
        Cluster c = cluster(instance);
        Topic t = topic(c, name);
        if (t.internal) {
            throw ApiException.badRequest("Internal topics cannot be deleted");
        }
        c.topics.remove(name);
        c.groups.values().forEach(g -> g.committed.remove(name));
    }

    @Override
    public synchronized void purgeTopic(KafkaInstance instance, String name, Map<Integer, Long> beforeOffsets) {
        Topic t = topic(cluster(instance), name);
        for (int p = 0; p < t.partitions(); p++) {
            Long target = beforeOffsets == null || beforeOffsets.isEmpty() ? Long.valueOf(t.latest[p]) : beforeOffsets.get(p);
            if (target != null) t.earliest[p] = Math.min(Math.max(target, t.earliest[p]), t.latest[p]);
        }
    }

    // ------------------------------------------------------------------ consumer groups

    @Override
    public synchronized List<ConsumerGroupSummary> consumerGroups(KafkaInstance instance) {
        Cluster c = cluster(instance);
        return c.groups.values().stream().map(g -> new ConsumerGroupSummary(g.id, state(c, g), g.members,
                List.copyOf(g.committed.keySet()), lag(c, g))).toList();
    }

    @Override
    public synchronized ConsumerGroupDetail consumerGroup(KafkaInstance instance, String groupId) {
        Cluster c = cluster(instance);
        Group g = group(c, groupId);
        List<OffsetLag> offsets = new ArrayList<>();
        g.committed.forEach((topic, committed) -> {
            Topic t = c.topics.get(topic);
            for (int p = 0; p < committed.length; p++) {
                long end = t == null || p >= t.latest.length ? committed[p] : t.latest[p];
                offsets.add(new OffsetLag(topic, p, committed[p], end, Math.max(0, end - committed[p])));
            }
        });
        List<GroupMember> members = new ArrayList<>();
        for (int m = 0; m < g.members; m++) {
            int idx = m;
            List<String> assignments = offsets.stream().filter(o -> o.partition() % g.members == idx)
                    .map(o -> o.topic() + "-" + o.partition()).toList();
            members.add(new GroupMember(g.id + "-" + (m + 1) + "-" + Integer.toHexString(g.id.hashCode() + m),
                    g.id + "-client-" + (m + 1), "/10.20.10." + (10 + m), assignments));
        }
        return new ConsumerGroupDetail(g.id, state(c, g), 1, members, offsets, lag(c, g));
    }

    @Override
    public synchronized void resetOffsets(KafkaInstance instance, String groupId, ResetOffsetsRequest request) {
        Cluster c = cluster(instance);
        Group g = group(c, groupId);
        if (g.members > 0) {
            throw ApiException.conflict("Consumer group " + groupId + " has " + g.members + " active member(s) - stop consumers before resetting offsets");
        }
        Topic t = topic(c, request.topic());
        long[] committed = new long[t.partitions()];
        for (int p = 0; p < committed.length; p++) {
            committed[p] = switch (request.strategy()) {
                case EARLIEST -> t.earliest[p];
                case LATEST -> t.latest[p];
                case OFFSET -> Math.min(Math.max(request.value() == null ? 0 : request.value(), t.earliest[p]), t.latest[p]);
                case TIMESTAMP -> t.earliest[p] + (t.latest[p] - t.earliest[p]) / 2;
            };
        }
        g.committed.put(t.name, committed);
    }

    @Override
    public synchronized void deleteConsumerGroup(KafkaInstance instance, String groupId) {
        Cluster c = cluster(instance);
        Group g = group(c, groupId);
        if (g.members > 0) {
            throw ApiException.conflict("Consumer group " + groupId + " is not empty");
        }
        c.groups.remove(groupId);
    }

    private static String state(Cluster c, Group g) {
        return g.members == 0 ? "EMPTY" : "STABLE";
    }

    private static long lag(Cluster c, Group g) {
        long lag = 0;
        for (Map.Entry<String, long[]> e : g.committed.entrySet()) {
            Topic t = c.topics.get(e.getKey());
            if (t == null) continue;
            for (int p = 0; p < e.getValue().length && p < t.latest.length; p++) lag += Math.max(0, t.latest[p] - e.getValue()[p]);
        }
        return lag;
    }

    // ------------------------------------------------------------------ Kafka Connect

    @Override
    public synchronized List<Connector> connectors(KafkaInstance instance) {
        requireConnect(instance);
        Cluster c = cluster(instance);
        return c.connectors.values().stream().map(s -> toDto(c, s)).toList();
    }

    @Override
    public synchronized Connector connector(KafkaInstance instance, String name) {
        requireConnect(instance);
        Cluster c = cluster(instance);
        return toDto(c, connectorState(c, name));
    }

    @Override
    public synchronized Connector createConnector(KafkaInstance instance, String name, Map<String, String> config) {
        requireConnect(instance);
        Cluster c = cluster(instance);
        if (c.connectors.containsKey(name)) {
            throw ApiException.conflict("Connector " + name + " already exists");
        }
        if (!config.containsKey("connector.class")) {
            throw ApiException.badRequest("connector.class is required");
        }
        String cls = config.get("connector.class").toLowerCase();
        String type = cls.contains("sink") ? "sink" : "source";
        int tasks = Integer.parseInt(config.getOrDefault("tasks.max", "1"));
        ConnectorState s = new ConnectorState(name, type, config, Math.max(1, Math.min(tasks, 10)));
        c.connectors.put(name, s);
        if ("sink".equals(type) && config.containsKey("topics")) {
            group(c, "connect-" + name, s.taskStates.size(), config.get("topics").split(","));
        }
        return toDto(c, s);
    }

    @Override
    public synchronized Connector updateConnectorConfig(KafkaInstance instance, String name, Map<String, String> config) {
        requireConnect(instance);
        Cluster c = cluster(instance);
        ConnectorState s = connectorState(c, name);
        s.config.clear();
        s.config.putAll(config);
        return toDto(c, s);
    }

    @Override
    public synchronized void deleteConnector(KafkaInstance instance, String name) {
        requireConnect(instance);
        Cluster c = cluster(instance);
        connectorState(c, name);
        c.connectors.remove(name);
    }

    @Override
    public synchronized void restartConnector(KafkaInstance instance, String name, boolean includeTasks, boolean onlyFailed) {
        requireConnect(instance);
        ConnectorState s = connectorState(cluster(instance), name);
        if (!"PAUSED".equals(s.state)) s.state = "RUNNING";
        if (includeTasks) {
            s.taskStates.replaceAll(t -> onlyFailed && !"FAILED".equals(t) ? t : ("PAUSED".equals(s.state) ? "PAUSED" : "RUNNING"));
            s.trace = null;
        }
    }

    @Override
    public synchronized void restartTask(KafkaInstance instance, String name, int taskId) {
        requireConnect(instance);
        ConnectorState s = connectorState(cluster(instance), name);
        if (taskId < 0 || taskId >= s.taskStates.size()) throw ApiException.notFound("Task " + taskId);
        s.taskStates.set(taskId, "PAUSED".equals(s.state) ? "PAUSED" : "RUNNING");
        if (!s.taskStates.contains("FAILED")) s.trace = null;
    }

    @Override
    public synchronized void pauseConnector(KafkaInstance instance, String name) {
        requireConnect(instance);
        ConnectorState s = connectorState(cluster(instance), name);
        s.state = "PAUSED";
        s.taskStates.replaceAll(t -> "FAILED".equals(t) ? t : "PAUSED");
    }

    @Override
    public synchronized void resumeConnector(KafkaInstance instance, String name) {
        requireConnect(instance);
        ConnectorState s = connectorState(cluster(instance), name);
        s.state = "RUNNING";
        s.taskStates.replaceAll(t -> "FAILED".equals(t) ? t : "RUNNING");
    }

    @Override
    public List<Map<String, Object>> connectorPlugins(KafkaInstance instance) {
        requireConnect(instance);
        return List.of(
                Map.of("class", "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector", "type", "sink", "version", "14.1.2"),
                Map.of("class", "io.confluent.connect.jdbc.JdbcSinkConnector", "type", "sink", "version", "10.8.0"),
                Map.of("class", "io.confluent.connect.s3.S3SinkConnector", "type", "sink", "version", "10.5.17"),
                Map.of("class", "io.debezium.connector.postgresql.PostgresConnector", "type", "source", "version", "3.1.0.Final"),
                Map.of("class", "org.apache.kafka.connect.mirror.MirrorSourceConnector", "type", "source", "version", "3.9.0"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized RawResponse connectRaw(KafkaInstance instance, String method, String path, Object body) {
        requireConnect(instance);
        String url = instance.connectUrls().get(0) + path;
        String clean = path.split("\\?")[0].replaceAll("/+$", "");
        String[] parts = clean.isEmpty() ? new String[0] : clean.substring(1).split("/");
        String m = method.toUpperCase();
        try {
            Object result;
            int status = 200;
            if (parts.length == 0) {
                result = Map.of("version", "3.9.0-mock", "commit", "mock", "kafka_cluster_id", cluster(instance).clusterId);
            } else if (parts[0].equals("connector-plugins")) {
                result = connectorPlugins(instance);
            } else if (parts[0].equals("connectors") && parts.length == 1 && m.equals("GET")) {
                result = cluster(instance).connectors.keySet();
            } else if (parts[0].equals("connectors") && parts.length == 1 && m.equals("POST")) {
                Map<String, Object> req = (Map<String, Object>) body;
                Map<String, String> cfg = new LinkedHashMap<>();
                ((Map<String, Object>) req.get("config")).forEach((k, v) -> cfg.put(k, String.valueOf(v)));
                result = createConnector(instance, String.valueOf(req.get("name")), cfg);
                status = 201;
            } else if (parts[0].equals("connectors") && parts.length >= 2) {
                String name = parts[1];
                String sub = parts.length > 2 ? parts[2] : "";
                result = switch (m + " " + sub) {
                    case "GET " -> connector(instance, name);
                    case "GET status" -> connector(instance, name);
                    case "GET config" -> connector(instance, name).config();
                    case "PUT config" -> {
                        Map<String, String> cfg = new LinkedHashMap<>();
                        ((Map<String, Object>) body).forEach((k, v) -> cfg.put(k, String.valueOf(v)));
                        yield updateConnectorConfig(instance, name, cfg);
                    }
                    case "DELETE " -> {
                        deleteConnector(instance, name);
                        yield null;
                    }
                    case "POST restart" -> {
                        restartConnector(instance, name, path.contains("includeTasks=true"), path.contains("onlyFailed=true"));
                        yield Map.of("restarted", name);
                    }
                    case "PUT pause" -> {
                        pauseConnector(instance, name);
                        yield null;
                    }
                    case "PUT resume" -> {
                        resumeConnector(instance, name);
                        yield null;
                    }
                    default -> throw new ApiException(HttpStatus.NOT_FOUND, "HTTP 404 Not Found");
                };
                if (result == null) status = 204;
            } else {
                throw new ApiException(HttpStatus.NOT_FOUND, "HTTP 404 Not Found");
            }
            return new RawResponse(status, result, url, 5);
        } catch (ApiException e) {
            return new RawResponse(e.getStatus().value(), Map.of("error_code", e.getStatus().value(), "message", e.getMessage()), url, 5);
        }
    }

    @Override
    public void evict(Long instanceId) {
        // Connection details changed - keep simulated state, nothing cached to drop.
    }

    // ------------------------------------------------------------------ helpers

    private Connector toDto(Cluster c, ConnectorState s) {
        List<ConnectorTask> tasks = new ArrayList<>();
        for (int i = 0; i < s.taskStates.size(); i++) {
            String st = s.taskStates.get(i);
            tasks.add(new ConnectorTask(i, st, "10.20.2.21:8083", "FAILED".equals(st) ? s.trace : null));
        }
        Long lag = null;
        if ("sink".equals(s.type)) {
            Group g = c.groups.get("connect-" + s.name);
            lag = g == null ? 0L : lag(c, g);
        }
        return new Connector(s.name, s.type, s.state, "10.20.2.21:8083", tasks, Map.copyOf(s.config), lag);
    }

    private static Topic topic(Cluster c, String name) {
        Topic t = c.topics.get(name);
        if (t == null) throw ApiException.notFound("Topic '" + name + "'");
        return t;
    }

    private static Group group(Cluster c, String id) {
        Group g = c.groups.get(id);
        if (g == null) throw ApiException.notFound("Consumer group '" + id + "'");
        return g;
    }

    private static ConnectorState connectorState(Cluster c, String name) {
        ConnectorState s = c.connectors.get(name);
        if (s == null) throw ApiException.notFound("Connector '" + name + "'");
        return s;
    }

    private static void requireConnect(KafkaInstance instance) {
        if (instance.connectUrls().isEmpty()) {
            throw ApiException.badRequest("No Connect / sink IPs configured for '" + instance.name() + "' in Inventory");
        }
    }
}
