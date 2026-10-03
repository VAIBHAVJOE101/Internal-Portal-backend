package com.platform.portal.settings;

import java.util.List;

/**
 * Integration setting types and the fields each one exposes in the Settings UI.
 * Fields flagged {@code secret} are encrypted at rest and never returned to the browser.
 */
public enum SettingType {

    GITHUB("GitHub", true, List.of(
            new Field("org", "Organization", false, true, "acme-platform", "GitHub organization the portal manages"),
            new Field("apiUrl", "API URL", false, false, "https://api.github.com", "Change for GitHub Enterprise Server"),
            new Field("token", "Service token (PAT / App token)", true, false, "ghp_...",
                    "Fallback token for background jobs. User actions use the signed-in user's OAuth token."))),

    AZURE_DEVOPS("Azure Boards", true, List.of(
            new Field("baseUrl", "Base URL", false, false, "https://dev.azure.com", null),
            new Field("organization", "Organization", false, true, "contoso", null),
            new Field("project", "Project", false, true, "Platform", null),
            new Field("team", "Team", false, true, "Platform Team", null),
            new Field("boardColumns", "Board columns", false, false, "New,Active,Resolved,Closed",
                    "Comma separated state order for the sprint board"),
            new Field("startDateField", "Start date field", false, false, "Microsoft.VSTS.Scheduling.StartDate", null),
            new Field("endDateField", "End date field", false, false, "Microsoft.VSTS.Scheduling.FinishDate", null),
            new Field("pat", "Personal access token", true, true, null, "Scopes: Work Items (Read & Write), Project and Team (Read)"))),

    COSMOS("Cosmos DB (API Gateway routes)", true, List.of(
            new Field("endpoint", "Account endpoint", false, true, "https://acct.documents.azure.com:443/", null),
            new Field("database", "Database", false, true, "gateway", null),
            new Field("container", "Container", false, true, "routes", null),
            new Field("partitionKeyPath", "Partition key path", false, false, "/id", "Path of the container partition key"),
            new Field("operationsPath", "Operations array path", false, false, "operations",
                    "Leave empty when each document is one operation"),
            new Field("operationIdField", "Operation id field", false, false, "operationId", "Dot path inside the operation"),
            new Field("methodField", "HTTP method field", false, false, "method", null),
            new Field("publicUrlField", "Public URL field", false, false, "publicUrl", null),
            new Field("internalUrlField", "Internal / backend URL field", false, false, "backendUrl", null),
            new Field("kafkaFields", "Kafka mapping fields", false, false, "kafka.topic,kafka.cluster",
                    "Comma separated dot paths that hold the Kafka mapping"),
            new Field("key", "Account key", true, true, null, null))),

    NOTIFICATIONS("Alert notifications", true, List.of(
            new Field("webhookUrl", "Webhook URL (Teams / Slack)", true, false, "https://...", "Receives new WARNING and CRITICAL alerts"),
            new Field("minSeverity", "Minimum severity", false, false, "WARNING", "CRITICAL, WARNING or INFO"))),

    KAFKA_CREDENTIAL("Kafka credential", false, List.of(
            new Field("username", "SASL username", false, false, null, null),
            new Field("password", "SASL password", true, false, null, null),
            new Field("truststoreLocation", "Truststore path (in pod)", false, false, "/etc/kafka/truststore.jks", null),
            new Field("truststorePassword", "Truststore password", true, false, null, null)));

    private final String label;
    private final boolean singleton;
    private final List<Field> fields;

    SettingType(String label, boolean singleton, List<Field> fields) {
        this.label = label;
        this.singleton = singleton;
        this.fields = fields;
    }

    public String label() { return label; }

    public boolean singleton() { return singleton; }

    public List<Field> fields() { return fields; }

    public boolean isSecret(String key) {
        return fields.stream().anyMatch(f -> f.key().equals(key) && f.secret());
    }

    public record Field(String key, String label, boolean secret, boolean required, String placeholder, String help) {
    }
}
