package com.platform.portal.inventory;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.platform.portal.config.PortalProperties;
import com.platform.portal.inventory.InventoryDtos.ColumnRequest;
import com.platform.portal.inventory.InventoryDtos.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Ensures system inventory pages exist on every start. In mock mode it also seeds the default
 * inventory pages (Secrets, Servers, IP Addresses, Applications, Services) with demo records.
 */
@Component
@Order(1)
public class InventorySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InventorySeeder.class);

    private final InventoryService inventory;
    private final PortalProperties properties;

    public InventorySeeder(InventoryService inventory, PortalProperties properties) {
        this.inventory = inventory;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean firstStart = !inventory.hasPages();
        SystemPages.all().forEach(inventory::ensureSystemPage);
        if (firstStart) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "system", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
            try {
                seedDefaultPages(properties.isMock());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private void seedDefaultPages(boolean withDemoData) {
        log.info("Seeding default inventory pages (demo data: {})", withDemoData);
        inventory.createPage(new PageRequest("Secrets", "secrets", "Secret and certificate metadata with expiry tracking. Values are never stored.",
                "key-round", "Security", List.of(
                col("name", "Name", ColumnType.TEXT, true, null),
                col("type", "Type", ColumnType.SELECT, true, SystemPages.choices("Client secret", "Certificate", "API key", "PAT", "Password", "SSH key")),
                col("location", "Stored in", ColumnType.TEXT, false, null),
                col("application", "Application", ColumnType.TEXT, false, null),
                col("owner", "Owner", ColumnType.EMAIL, false, null),
                col("environment", "Environment", ColumnType.SELECT, false, SystemPages.choices("dev", "qa", "uat", "prod")),
                new ColumnRequest("expiresOn", "Expires on", ColumnType.DATE, true, true, null, null, true, "Alerts at 30 / 7 / 0 days"),
                col("rotationDays", "Rotation (days)", ColumnType.NUMBER, false, null))));
        inventory.createPage(new PageRequest("Servers", "servers", "Virtual machines and hosts", "server", "Infrastructure", List.of(
                col("hostname", "Hostname", ColumnType.TEXT, true, null),
                col("ip", "IP address", ColumnType.IP, false, null),
                col("os", "OS", ColumnType.SELECT, false, SystemPages.choices("Ubuntu 24.04", "RHEL 9", "Windows Server 2022", "Debian 12")),
                col("environment", "Environment", ColumnType.SELECT, false, SystemPages.choices("dev", "qa", "uat", "prod")),
                col("cpu", "vCPU", ColumnType.NUMBER, false, null),
                col("memoryGb", "Memory (GB)", ColumnType.NUMBER, false, null),
                col("owner", "Owner", ColumnType.EMAIL, false, null),
                col("tags", "Tags", ColumnType.LIST, false, null))));
        inventory.createPage(new PageRequest("IP Addresses", "ip-addresses", "IP allocations, ranges and reservations", "network", "Infrastructure", List.of(
                col("address", "Address / CIDR", ColumnType.IP, true, null),
                col("assignedTo", "Assigned to", ColumnType.TEXT, false, null),
                col("vnet", "VNet / Subnet", ColumnType.TEXT, false, null),
                col("type", "Type", ColumnType.SELECT, false, SystemPages.choices("Static", "Reserved", "Range", "Public")),
                col("environment", "Environment", ColumnType.SELECT, false, SystemPages.choices("dev", "qa", "uat", "prod")),
                col("notes", "Notes", ColumnType.LONGTEXT, false, null))));
        inventory.createPage(new PageRequest("Applications", "applications", "Business applications and ownership", "app-window", "Workloads", List.of(
                col("name", "Name", ColumnType.TEXT, true, null),
                col("team", "Owning team", ColumnType.TEXT, false, null),
                col("tier", "Tier", ColumnType.SELECT, false, SystemPages.choices("Tier 0", "Tier 1", "Tier 2", "Tier 3")),
                col("repo", "Repository", ColumnType.URL, false, null),
                col("namespace", "K8s namespace", ColumnType.TEXT, false, null),
                col("status", "Status", ColumnType.SELECT, false, SystemPages.choices("Active", "Deprecated", "Planned")))));
        inventory.createPage(new PageRequest("Services", "services", "Deployed services and endpoints", "boxes", "Workloads", List.of(
                col("name", "Service", ColumnType.TEXT, true, null),
                col("application", "Application", ColumnType.TEXT, false, null),
                col("endpoint", "Endpoint", ColumnType.URL, false, null),
                col("port", "Port", ColumnType.NUMBER, false, null),
                col("environment", "Environment", ColumnType.SELECT, false, SystemPages.choices("dev", "qa", "uat", "prod")),
                col("healthCheck", "Health check", ColumnType.URL, false, null),
                col("critical", "Business critical", ColumnType.BOOLEAN, false, null))));

        if (!withDemoData) {
            return;
        }
        LocalDate today = LocalDate.now();
        kafka("Kafka PROD (West EU)", "prod", List.of("10.20.1.11:9093", "10.20.1.12:9093", "10.20.1.13:9093"),
                List.of("http://10.20.2.21:8083", "http://10.20.2.22:8083"), "SASL_SSL", "SCRAM-SHA-512", "kafka-prod");
        kafka("Kafka UAT", "uat", List.of("10.30.1.11:9092", "10.30.1.12:9092", "10.30.1.13:9092"),
                List.of("http://10.30.2.21:8083"), "SASL_SSL", "SCRAM-SHA-256", "kafka-uat");
        kafka("Kafka DEV", "dev", List.of("10.40.1.11:9092"), List.of("http://10.40.2.21:8083"), "PLAINTEXT", null, null);

        secret("orders-api client secret", "Client secret", "kv-platform-prod", "Orders", "orders-team@acme.io", "prod", today.minusDays(2), 180);
        secret("api.acme.io TLS", "Certificate", "kv-edge-prod", "API Gateway", "edge@acme.io", "prod", today.plusDays(5), 365);
        secret("github-actions deploy PAT", "PAT", "GitHub org secrets", "CI/CD", "devops@acme.io", "prod", today.plusDays(19), 90);
        secret("cosmos-routes read key", "API key", "kv-platform-prod", "Kafka Portal", "devops@acme.io", "prod", today.plusDays(27), 90);
        secret("payments-sp secret", "Client secret", "kv-payments-uat", "Payments", "payments@acme.io", "uat", today.plusDays(64), 180);
        secret("grafana admin", "Password", "kv-observability", "Observability", "sre@acme.io", "prod", today.plusDays(140), 365);
        secret("bastion ssh key", "SSH key", "kv-infra", "Infrastructure", "sre@acme.io", "prod", today.plusDays(300), 365);

        record("servers", "hostname", "aks-prod-node-01", "ip", "10.20.10.4", "os", "Ubuntu 24.04", "environment", "prod", "cpu", 16, "memoryGb", 64, "owner", "sre@acme.io", "tags", List.of("aks", "system"));
        record("servers", "hostname", "aks-prod-node-02", "ip", "10.20.10.5", "os", "Ubuntu 24.04", "environment", "prod", "cpu", 16, "memoryGb", 64, "owner", "sre@acme.io", "tags", List.of("aks", "user"));
        record("servers", "hostname", "kafka-broker-01", "ip", "10.20.1.11", "os", "RHEL 9", "environment", "prod", "cpu", 8, "memoryGb", 32, "owner", "streaming@acme.io", "tags", List.of("kafka"));
        record("servers", "hostname", "connect-worker-01", "ip", "10.20.2.21", "os", "RHEL 9", "environment", "prod", "cpu", 4, "memoryGb", 16, "owner", "streaming@acme.io", "tags", List.of("kafka-connect"));
        record("servers", "hostname", "jenkins-legacy", "ip", "10.40.5.9", "os", "Windows Server 2022", "environment", "dev", "cpu", 4, "memoryGb", 16, "owner", "devops@acme.io", "tags", List.of("legacy"));
        record("servers", "hostname", "bastion-weu", "ip", "10.20.0.4", "os", "Debian 12", "environment", "prod", "cpu", 2, "memoryGb", 4, "owner", "sre@acme.io", "tags", List.of("access"));

        record("ip-addresses", "address", "10.20.0.0/16", "assignedTo", "vnet-prod-weu", "vnet", "vnet-prod-weu", "type", "Range", "environment", "prod");
        record("ip-addresses", "address", "10.20.1.0/24", "assignedTo", "Kafka brokers", "vnet", "snet-kafka", "type", "Range", "environment", "prod");
        record("ip-addresses", "address", "10.20.2.21", "assignedTo", "connect-worker-01", "vnet", "snet-kafka-connect", "type", "Static", "environment", "prod");
        record("ip-addresses", "address", "20.105.33.12", "assignedTo", "api.acme.io ingress", "vnet", "-", "type", "Public", "environment", "prod");
        record("ip-addresses", "address", "10.30.0.0/16", "assignedTo", "vnet-uat-weu", "vnet", "vnet-uat-weu", "type", "Range", "environment", "uat");

        record("applications", "name", "Orders", "team", "Commerce", "tier", "Tier 1", "repo", "https://github.com/acme-platform/orders", "namespace", "orders", "status", "Active");
        record("applications", "name", "Payments", "team", "Payments", "tier", "Tier 0", "repo", "https://github.com/acme-platform/payments", "namespace", "payments", "status", "Active");
        record("applications", "name", "API Gateway", "team", "Edge", "tier", "Tier 0", "repo", "https://github.com/acme-platform/api-gateway", "namespace", "gateway", "status", "Active");
        record("applications", "name", "Legacy Reports", "team", "Data", "tier", "Tier 3", "repo", "https://github.com/acme-platform/reports", "namespace", "reports", "status", "Deprecated");

        record("services", "name", "orders-api", "application", "Orders", "endpoint", "https://api.acme.io/orders", "port", 8080, "environment", "prod", "healthCheck", "http://orders-api.orders.svc/actuator/health", "critical", true);
        record("services", "name", "payments-api", "application", "Payments", "endpoint", "https://api.acme.io/payments", "port", 8080, "environment", "prod", "healthCheck", "http://payments-api.payments.svc/health", "critical", true);
        record("services", "name", "gateway", "application", "API Gateway", "endpoint", "https://api.acme.io", "port", 443, "environment", "prod", "healthCheck", "https://api.acme.io/health", "critical", true);
        record("services", "name", "reports-worker", "application", "Legacy Reports", "endpoint", "http://reports.reports.svc", "port", 80, "environment", "dev", "critical", false);
    }

    private void kafka(String name, String env, List<String> brokers, List<String> connect, String protocol, String mechanism, String credentialRef) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("environment", env);
        data.put("brokers", brokers);
        data.put("connectUrls", connect);
        data.put("securityProtocol", protocol);
        data.put("saslMechanism", mechanism);
        data.put("credentialRef", credentialRef);
        data.put("enabled", true);
        inventory.createRecord(SystemPages.KAFKA_INSTANCES, data);
    }

    private void secret(String name, String type, String location, String app, String owner, String env, LocalDate expires, int rotation) {
        record("secrets", "name", name, "type", type, "location", location, "application", app, "owner", owner,
                "environment", env, "expiresOn", expires.toString(), "rotationDays", rotation);
    }

    private void record(String slug, Object... kv) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            data.put((String) kv[i], kv[i + 1]);
        }
        inventory.createRecord(slug, data);
    }

    private static ColumnRequest col(String key, String label, ColumnType type, boolean required, Map<String, Object> options) {
        return new ColumnRequest(key, label, type, required, true, null, options, false, null);
    }
}
