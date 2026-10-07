package com.platform.portal.appkafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.platform.portal.appkafka.RoutesModels.ApplyResult;
import com.platform.portal.appkafka.RoutesModels.BulkChangeRequest;
import com.platform.portal.appkafka.RoutesModels.Change;
import com.platform.portal.appkafka.RoutesModels.ChangeResult;
import com.platform.portal.appkafka.RoutesModels.RouteRow;
import com.platform.portal.appkafka.RoutesModels.RoutesConfig;
import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.Strings;
import org.springframework.stereotype.Service;

/**
 * Application Kafka Portal: lists API Gateway operations with their Kafka mappings and performs
 * single or bulk remaps with a dry-run preview. Every applied change is audited with before/after values.
 */
@Service
public class AppKafkaService {

    private static final int MAX_BULK = 500;

    private final RoutesGateway gateway;
    private final AuditService audit;

    public AppKafkaService(RoutesGateway gateway, AuditService audit) {
        this.gateway = gateway;
        this.audit = audit;
    }

    public RoutesConfig config() {
        return gateway.config();
    }

    public List<RouteRow> routes(String q, String api, String method, String field, String value) {
        String needle = Strings.isBlank(q) ? null : q.toLowerCase();
        return gateway.rows().stream()
                .filter(r -> needle == null || contains(r.operationId(), needle) || contains(r.publicUrl(), needle)
                        || contains(r.internalUrl(), needle) || contains(r.api(), needle)
                        || r.mappings().values().stream().anyMatch(v -> contains(v, needle)))
                .filter(r -> Strings.isBlank(api) || api.equals(r.api()))
                .filter(r -> Strings.isBlank(method) || method.equalsIgnoreCase(r.method()))
                .filter(r -> Strings.isBlank(field) || Strings.isBlank(value) || contains(r.mappings().get(field), value.toLowerCase()))
                .toList();
    }

    /** Distinct values currently used per mapping field (for autocomplete). */
    public Map<String, List<String>> values() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        List<RouteRow> rows = gateway.rows();
        for (String field : gateway.config().mappingFields()) {
            values.put(field, rows.stream().map(r -> r.mappings().get(field)).filter(Objects::nonNull)
                    .collect(Collectors.toCollection(TreeSet::new)).stream().toList());
        }
        return values;
    }

    public List<Change> preview(BulkChangeRequest request) {
        validate(request);
        Map<String, RouteRow> byId = gateway.rows().stream().collect(Collectors.toMap(RouteRow::id, Function.identity()));
        List<Change> changes = new ArrayList<>();
        for (String id : request.rowIds()) {
            RouteRow row = byId.get(id);
            if (row == null) {
                throw ApiException.conflict("Route " + id + " no longer exists - refresh the list");
            }
            String before = row.mappings().get(request.field());
            String after = switch (request.mode()) {
                case SET -> request.value().trim();
                case CLEAR -> null;
                case REPLACE -> before == null ? null : before.replace(request.find(), request.replace() == null ? "" : request.replace());
            };
            if (!Objects.equals(before, after)) {
                changes.add(new Change(id, row.operationId(), row.publicUrl(), request.field(), before, after));
            }
        }
        return changes;
    }

    public ApplyResult apply(BulkChangeRequest request) {
        List<Change> changes = preview(request);
        if (changes.isEmpty()) {
            return new ApplyResult(0, 0, 0, List.of());
        }
        Map<String, Map<String, String>> payload = new LinkedHashMap<>();
        for (Change c : changes) {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put(c.field(), c.after());
            payload.put(c.rowId(), fields);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", request.field());
        details.put("mode", request.mode().name());
        details.put("changes", changes.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operation", c.operationId());
            m.put("before", c.before());
            m.put("after", c.after());
            return m;
        }).toList());
        String target = changes.size() == 1 ? changes.get(0).operationId() : changes.size() + " operations";
        List<ChangeResult> raw = audit.track(changes.size() == 1 ? "APPKAFKA_REMAP" : "APPKAFKA_BULK_REMAP", "gateway-route", target,
                details, () -> gateway.apply(payload));
        Map<String, Change> byRow = changes.stream().collect(Collectors.toMap(Change::rowId, Function.identity()));
        List<ChangeResult> results = raw.stream()
                .map(r -> new ChangeResult(r.rowId(), byRow.containsKey(r.rowId()) ? byRow.get(r.rowId()).operationId() : r.operationId(),
                        r.success(), r.message()))
                .toList();
        int ok = (int) results.stream().filter(ChangeResult::success).count();
        return new ApplyResult(changes.size(), ok, results.size() - ok, results);
    }

    private void validate(BulkChangeRequest r) {
        if (r.rowIds().size() > MAX_BULK) {
            throw ApiException.badRequest("At most " + MAX_BULK + " operations can be changed at once");
        }
        if (!gateway.config().mappingFields().contains(r.field())) {
            throw ApiException.badRequest("Unknown mapping field '" + r.field() + "'");
        }
        switch (r.mode()) {
            case SET -> {
                if (Strings.isBlank(r.value())) throw ApiException.badRequest("value is required for SET");
                if (r.field().toLowerCase().contains("topic") && !r.value().trim().matches("[a-zA-Z0-9._-]{1,249}")) {
                    throw ApiException.badRequest("'" + r.value() + "' is not a valid Kafka topic name");
                }
            }
            case REPLACE -> {
                if (Strings.isBlank(r.find())) throw ApiException.badRequest("find is required for REPLACE");
            }
            case CLEAR -> {
            }
        }
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }
}
