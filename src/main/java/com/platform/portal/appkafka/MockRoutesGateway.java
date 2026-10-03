package com.platform.portal.appkafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.platform.portal.appkafka.RoutesModels.ChangeResult;
import com.platform.portal.appkafka.RoutesModels.RouteRow;
import com.platform.portal.appkafka.RoutesModels.RoutesConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** In-memory API Gateway route documents for mock mode. */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "mock")
public class MockRoutesGateway implements RoutesGateway {

    private static final List<String> FIELDS = List.of("kafka.topic", "kafka.cluster");

    private final Map<String, RouteRow> rows = new LinkedHashMap<>();

    public MockRoutesGateway() {
        Object[][] apis = {
                {"orders-api", "orders", new String[]{"createOrder:POST:/orders", "getOrder:GET:/orders/{id}", "updateOrder:PUT:/orders/{id}",
                        "cancelOrder:POST:/orders/{id}/cancel", "listOrders:GET:/orders", "orderEvents:POST:/orders/{id}/events"}},
                {"payments-api", "payments", new String[]{"authorizePayment:POST:/payments/authorize", "capturePayment:POST:/payments/{id}/capture",
                        "refundPayment:POST:/payments/{id}/refund", "getPayment:GET:/payments/{id}", "paymentWebhook:POST:/payments/webhook"}},
                {"customers-api", "customers", new String[]{"createCustomer:POST:/customers", "updateCustomer:PATCH:/customers/{id}",
                        "deleteCustomer:DELETE:/customers/{id}", "getCustomer:GET:/customers/{id}"}},
                {"inventory-api", "inventory", new String[]{"reserveStock:POST:/inventory/reserve", "releaseStock:POST:/inventory/release",
                        "stockLevel:GET:/inventory/{sku}", "adjustStock:PUT:/inventory/{sku}"}},
                {"notifications-api", "notifications", new String[]{"sendEmail:POST:/notify/email", "sendSms:POST:/notify/sms",
                        "sendPush:POST:/notify/push"}},
                {"shipping-api", "shipping", new String[]{"createShipment:POST:/shipments", "trackShipment:GET:/shipments/{id}/track",
                        "shipmentUpdate:POST:/shipments/{id}/status"}},
        };
        for (Object[] api : apis) {
            String apiName = (String) api[0];
            String domain = (String) api[1];
            String[] ops = (String[]) api[2];
            for (int i = 0; i < ops.length; i++) {
                String[] p = ops[i].split(":");
                String docId = "route-" + apiName;
                String rowId = docId + "#" + i;
                Map<String, String> mappings = new LinkedHashMap<>();
                boolean write = !p[1].equals("GET");
                mappings.put("kafka.topic", write ? domain + "." + toEvent(p[0]) : null);
                mappings.put("kafka.cluster", write ? (domain.equals("payments") ? "kafka-payments-prod" : "kafka-prod-weu") : null);
                rows.put(rowId, new RouteRow(rowId, docId, i, apiName, p[0], p[1], "https://api.acme.io" + p[2],
                        "http://" + apiName + "." + domain + ".svc.cluster.local:8080" + p[2], mappings));
            }
        }
        // a couple of legacy mappings to make bulk find/replace demos meaningful
        update("route-orders-api#5", "kafka.topic", "legacy.orders.events");
        update("route-shipping-api#2", "kafka.topic", "legacy.shipping.status");
        update("route-shipping-api#2", "kafka.cluster", "kafka-uat");
    }

    private static String toEvent(String operationId) {
        return operationId.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    private void update(String rowId, String field, String value) {
        RouteRow r = rows.get(rowId);
        Map<String, String> m = new LinkedHashMap<>(r.mappings());
        m.put(field, value);
        rows.put(rowId, new RouteRow(r.id(), r.docId(), r.index(), r.api(), r.operationId(), r.method(), r.publicUrl(), r.internalUrl(), m));
    }

    @Override
    public RoutesConfig config() {
        return new RoutesConfig("cosmos://gateway/routes (mock)", FIELDS, true);
    }

    @Override
    public synchronized List<RouteRow> rows() {
        return new ArrayList<>(rows.values());
    }

    @Override
    public synchronized List<ChangeResult> apply(Map<String, Map<String, String>> changes) {
        List<ChangeResult> results = new ArrayList<>();
        changes.forEach((rowId, fields) -> {
            if (!rows.containsKey(rowId)) {
                results.add(new ChangeResult(rowId, null, false, "Route no longer exists"));
                return;
            }
            fields.forEach((f, v) -> update(rowId, f, v));
            results.add(new ChangeResult(rowId, rows.get(rowId).operationId(), true, "Updated"));
        });
        return results;
    }
}
