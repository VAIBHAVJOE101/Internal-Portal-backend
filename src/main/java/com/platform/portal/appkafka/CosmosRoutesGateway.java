package com.platform.portal.appkafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.platform.portal.appkafka.RoutesModels.ChangeResult;
import com.platform.portal.appkafka.RoutesModels.RouteRow;
import com.platform.portal.appkafka.RoutesModels.RoutesConfig;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.Strings;
import com.platform.portal.settings.IntegrationProbe;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsChangedEvent;
import com.platform.portal.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reads API Gateway route documents from Cosmos DB. Field locations are configured in Settings
 * (dot paths), so the portal adapts to the gateway's document schema instead of hard-coding it.
 * Updates use optimistic concurrency (If-Match ETag) to avoid overwriting concurrent edits.
 */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "real", matchIfMissing = true)
public class CosmosRoutesGateway implements RoutesGateway, IntegrationProbe {

    private static final int MAX_DOCUMENTS = 5000;

    private final SettingsService settings;
    private volatile CachedClient cached;

    public CosmosRoutesGateway(SettingsService settings) {
        this.settings = settings;
    }

    private record CachedClient(String fingerprint, CosmosClient client) {
    }

    private record Cfg(String endpoint, String key, String database, String container, String partitionKeyPath,
                       String operationsPath, String operationIdField, String methodField, String publicUrlField,
                       String internalUrlField, List<String> mappingFields) {

        static Cfg of(Map<String, String> m, SettingsService s) {
            return new Cfg(s.require(m, "endpoint", SettingType.COSMOS), s.require(m, "key", SettingType.COSMOS),
                    s.require(m, "database", SettingType.COSMOS), s.require(m, "container", SettingType.COSMOS),
                    m.getOrDefault("partitionKeyPath", "/id"), Strings.trimToNull(m.get("operationsPath")),
                    m.getOrDefault("operationIdField", "operationId"), m.getOrDefault("methodField", "method"),
                    m.getOrDefault("publicUrlField", "publicUrl"), m.getOrDefault("internalUrlField", "backendUrl"),
                    Strings.asList(m.getOrDefault("kafkaFields", "kafka.topic")));
        }
    }

    // ------------------------------------------------------------------ API

    @Override
    public RoutesConfig config() {
        Map<String, String> m = settings.resolve(SettingType.COSMOS);
        boolean configured = !Strings.isBlank(m.get("endpoint")) && !Strings.isBlank(m.get("key"))
                && !Strings.isBlank(m.get("database")) && !Strings.isBlank(m.get("container"));
        String source = configured ? "cosmos://" + m.get("database") + "/" + m.get("container") : "Cosmos DB (not configured)";
        return new RoutesConfig(source, Strings.asList(m.getOrDefault("kafkaFields", "kafka.topic")), configured);
    }

    @Override
    public SettingType type() {
        return SettingType.COSMOS;
    }

    @Override
    public Map<String, Object> probe(Map<String, String> values) {
        Cfg cfg = Cfg.of(values, settings);
        CosmosContainer container = client(cfg).getDatabase(cfg.database()).getContainer(cfg.container());
        Integer count = container.queryItems("SELECT VALUE COUNT(1) FROM c", new CosmosQueryRequestOptions(), Integer.class)
                .stream().findFirst().orElse(0);
        return Map.of("database", cfg.database(), "container", cfg.container(), "documents", count);
    }

    @Override
    public List<RouteRow> rows() {
        Cfg cfg = Cfg.of(settings.resolve(SettingType.COSMOS), settings);
        CosmosContainer container = container(cfg);
        List<RouteRow> rows = new ArrayList<>();
        try {
            container.queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), ObjectNode.class)
                    .stream().limit(MAX_DOCUMENTS)
                    .forEach(doc -> rows.addAll(toRows(doc, cfg)));
        } catch (CosmosException e) {
            throw ApiException.upstream("Cosmos DB query failed: " + e.getShortMessage(), e);
        }
        return rows;
    }

    @Override
    public List<ChangeResult> apply(Map<String, Map<String, String>> changes) {
        Cfg cfg = Cfg.of(settings.resolve(SettingType.COSMOS), settings);
        CosmosContainer container = container(cfg);
        // group per document so each document is read and replaced once
        Map<String, Map<String, Map<String, String>>> byDoc = new LinkedHashMap<>();
        changes.forEach((rowId, fields) -> byDoc.computeIfAbsent(docId(rowId), d -> new LinkedHashMap<>()).put(rowId, fields));
        List<ChangeResult> results = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, String>>> entry : byDoc.entrySet()) {
            String docId = entry.getKey();
            try {
                SqlQuerySpec query = new SqlQuerySpec("SELECT * FROM c WHERE c.id = @id", List.of(new SqlParameter("@id", docId)));
                ObjectNode doc = container.queryItems(query, new CosmosQueryRequestOptions(), ObjectNode.class).stream().findFirst()
                        .orElseThrow(() -> ApiException.notFound("Document " + docId));
                String etag = doc.path("_etag").asText(null);
                for (Map.Entry<String, Map<String, String>> row : entry.getValue().entrySet()) {
                    ObjectNode target = operationNode(doc, cfg, index(row.getKey()));
                    row.getValue().forEach((path, value) -> setPath(target, path, value));
                }
                Object pk = valueAt(doc, cfg.partitionKeyPath().replaceFirst("^/", "").replace('/', '.'));
                CosmosItemRequestOptions options = new CosmosItemRequestOptions();
                if (etag != null) options.setIfMatchETag(etag);
                container.replaceItem(doc, docId, pk == null ? new PartitionKey(docId) : partitionKey(pk), options);
                entry.getValue().keySet().forEach(r -> results.add(new ChangeResult(r, null, true, "Updated")));
            } catch (CosmosException e) {
                String msg = e.getStatusCode() == 412 ? "Document changed concurrently - refresh and retry" : e.getShortMessage();
                entry.getValue().keySet().forEach(r -> results.add(new ChangeResult(r, null, false, msg)));
            } catch (RuntimeException e) {
                entry.getValue().keySet().forEach(r -> results.add(new ChangeResult(r, null, false, e.getMessage())));
            }
        }
        return results;
    }

    // ------------------------------------------------------------------ mapping

    private List<RouteRow> toRows(ObjectNode doc, Cfg cfg) {
        String docId = doc.path("id").asText();
        String api = text(doc, "apiName") != null ? text(doc, "apiName") : text(doc, "name");
        List<RouteRow> rows = new ArrayList<>();
        if (cfg.operationsPath() == null) {
            rows.add(row(docId, -1, api, doc, cfg));
        } else {
            JsonNode ops = node(doc, cfg.operationsPath());
            if (ops instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i) instanceof ObjectNode op) rows.add(row(docId, i, api, op, cfg));
                }
            }
        }
        return rows;
    }

    private static RouteRow row(String docId, int index, String api, ObjectNode op, Cfg cfg) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String field : cfg.mappingFields()) mappings.put(field, text(op, field));
        return new RouteRow(index < 0 ? docId : docId + "#" + index, docId, index, api, text(op, cfg.operationIdField()),
                text(op, cfg.methodField()), text(op, cfg.publicUrlField()), text(op, cfg.internalUrlField()), mappings);
    }

    private static ObjectNode operationNode(ObjectNode doc, Cfg cfg, int index) {
        if (index < 0 || cfg.operationsPath() == null) return doc;
        JsonNode ops = node(doc, cfg.operationsPath());
        if (!(ops instanceof ArrayNode array) || index >= array.size() || !(array.get(index) instanceof ObjectNode op)) {
            throw ApiException.conflict("Operation #" + index + " no longer exists in the document");
        }
        return op;
    }

    static void setPath(ObjectNode root, String path, String value) {
        String[] parts = path.split("\\.");
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = current.get(parts[i]);
            ObjectNode child;
            if (next instanceof ObjectNode existing) {
                child = existing;
            } else {
                child = JsonNodeFactory.instance.objectNode();
                current.set(parts[i], child);
            }
            current = child;
        }
        if (value == null) {
            current.remove(parts[parts.length - 1]);
        } else {
            current.put(parts[parts.length - 1], value);
        }
    }

    private static JsonNode node(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            current = current.get(part);
        }
        return current;
    }

    private static String text(JsonNode root, String path) {
        JsonNode n = node(root, path);
        return n == null || n.isNull() ? null : (n.isValueNode() ? n.asText() : n.toString());
    }

    private static Object valueAt(JsonNode root, String path) {
        JsonNode n = node(root, path);
        if (n == null || n.isNull()) return null;
        if (n.isNumber()) return n.numberValue();
        if (n.isBoolean()) return n.booleanValue();
        return n.asText();
    }

    private static PartitionKey partitionKey(Object value) {
        if (value instanceof Number n) return new PartitionKey(n.doubleValue());
        if (value instanceof Boolean b) return new PartitionKey(b);
        return new PartitionKey(value.toString());
    }

    static String docId(String rowId) {
        int hash = rowId.lastIndexOf('#');
        return hash < 0 ? rowId : rowId.substring(0, hash);
    }

    static int index(String rowId) {
        int hash = rowId.lastIndexOf('#');
        return hash < 0 ? -1 : Integer.parseInt(rowId.substring(hash + 1));
    }

    // ------------------------------------------------------------------ client cache

    private CosmosContainer container(Cfg cfg) {
        return client(cfg).getDatabase(cfg.database()).getContainer(cfg.container());
    }

    private synchronized CosmosClient client(Cfg cfg) {
        String fingerprint = cfg.endpoint() + "|" + Integer.toHexString(cfg.key().hashCode());
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.client();
        }
        close();
        CosmosClient client = new CosmosClientBuilder().endpoint(cfg.endpoint()).key(cfg.key()).gatewayMode().buildClient();
        cached = new CachedClient(fingerprint, client);
        return client;
    }

    @EventListener
    void onSettingsChange(SettingsChangedEvent event) {
        if (event.type() == SettingType.COSMOS) {
            close();
        }
    }

    @PreDestroy
    synchronized void close() {
        if (cached != null) {
            cached.client().close();
            cached = null;
        }
    }
}
