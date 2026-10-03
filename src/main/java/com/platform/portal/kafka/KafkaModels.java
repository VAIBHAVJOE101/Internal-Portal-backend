package com.platform.portal.kafka;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** DTOs exchanged between the Kafka gateways, service and REST API. */
public final class KafkaModels {

    private KafkaModels() {
    }

    /** A Kafka cluster as registered in the "Kafka Instances" inventory page. */
    public record KafkaInstance(Long id, String name, String environment, List<String> brokers, List<String> connectUrls,
                                String securityProtocol, String saslMechanism, String credentialRef, boolean enabled,
                                Map<String, Object> extra) {
    }

    public record ClusterOverview(KafkaInstance instance, String status, String clusterId, Integer controllerId,
                                 int brokersOnline, int brokersExpected, int topics, int partitions, int underReplicated,
                                 int offlinePartitions, int consumerGroups, int connectorsTotal, int connectorsFailed,
                                 String kafkaConnectVersion, String error, long latencyMs) {
    }

    public record BrokerInfo(int id, String host, int port, String rack, boolean controller) {
    }

    public record TopicSummary(String name, int partitions, int replicationFactor, boolean internal, int underReplicated,
                               String retentionMs, String cleanupPolicy) {
    }

    public record PartitionInfo(int partition, Integer leader, List<Integer> replicas, List<Integer> isr,
                                Long earliestOffset, Long latestOffset) {
    }

    public record ConfigEntry(String name, String value, String source, boolean isDefault, boolean readOnly, boolean sensitive) {
    }

    public record TopicDetail(String name, boolean internal, List<PartitionInfo> partitions, List<ConfigEntry> configs,
                              long messages) {
    }

    public record ConsumerGroupSummary(String groupId, String state, int members, List<String> topics, long totalLag) {
    }

    public record OffsetLag(String topic, int partition, Long committed, Long end, Long lag) {
    }

    public record GroupMember(String memberId, String clientId, String host, List<String> assignments) {
    }

    public record ConsumerGroupDetail(String groupId, String state, Integer coordinator, List<GroupMember> members,
                                      List<OffsetLag> offsets, long totalLag) {
    }

    public record ConnectorTask(int id, String state, String workerId, String trace) {
    }

    public record Connector(String name, String type, String state, String workerId, List<ConnectorTask> tasks,
                            Map<String, String> config, Long lag) {
    }

    public record RawResponse(int status, Object body, String url, long latencyMs) {
    }

    // ------------------------------------------------------------ requests

    public record CreateTopicRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,249}", message = "may only contain letters, digits, '.', '_' and '-'") String name,
            @Min(1) @Max(10_000) int partitions,
            @Min(1) @Max(10) short replicationFactor,
            Map<String, String> configs) {
    }

    public record AlterConfigsRequest(Map<String, String> set, List<String> delete) {
    }

    public record AddPartitionsRequest(@Min(1) @Max(10_000) int totalCount) {
    }

    /** Deletes records up to the given offset per partition; empty map means "everything currently in the topic". */
    public record PurgeRequest(Map<Integer, Long> beforeOffsets) {
    }

    public record ResetOffsetsRequest(@NotBlank String topic, @NotNull Strategy strategy, Long value) {
        public enum Strategy { EARLIEST, LATEST, OFFSET, TIMESTAMP }
    }

    public record ConnectorRequest(@NotBlank String name, @NotNull Map<String, String> config) {
    }

    public record RawRequest(@NotBlank String method, @NotBlank String path, Object body) {
    }
}
