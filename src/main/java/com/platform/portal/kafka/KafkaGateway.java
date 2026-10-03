package com.platform.portal.kafka;

import java.util.List;
import java.util.Map;

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

/**
 * Operations against one Kafka cluster (Admin API) and its Kafka Connect workers (REST).
 * Implemented by {@code RealKafkaGateway} and {@code MockKafkaGateway} depending on {@code portal.mode}.
 */
public interface KafkaGateway {

    ClusterOverview overview(KafkaInstance instance);

    List<BrokerInfo> brokers(KafkaInstance instance);

    // topics
    List<TopicSummary> topics(KafkaInstance instance);

    TopicDetail topic(KafkaInstance instance, String topic);

    void createTopic(KafkaInstance instance, CreateTopicRequest request);

    void alterTopicConfigs(KafkaInstance instance, String topic, Map<String, String> set, List<String> delete);

    void addPartitions(KafkaInstance instance, String topic, int totalCount);

    void deleteTopic(KafkaInstance instance, String topic);

    void purgeTopic(KafkaInstance instance, String topic, Map<Integer, Long> beforeOffsets);

    // consumer groups
    List<ConsumerGroupSummary> consumerGroups(KafkaInstance instance);

    ConsumerGroupDetail consumerGroup(KafkaInstance instance, String groupId);

    void resetOffsets(KafkaInstance instance, String groupId, ResetOffsetsRequest request);

    void deleteConsumerGroup(KafkaInstance instance, String groupId);

    // Kafka Connect
    List<Connector> connectors(KafkaInstance instance);

    Connector connector(KafkaInstance instance, String name);

    Connector createConnector(KafkaInstance instance, String name, Map<String, String> config);

    Connector updateConnectorConfig(KafkaInstance instance, String name, Map<String, String> config);

    void deleteConnector(KafkaInstance instance, String name);

    void restartConnector(KafkaInstance instance, String name, boolean includeTasks, boolean onlyFailed);

    void restartTask(KafkaInstance instance, String name, int taskId);

    void pauseConnector(KafkaInstance instance, String name);

    void resumeConnector(KafkaInstance instance, String name);

    List<Map<String, Object>> connectorPlugins(KafkaInstance instance);

    RawResponse connectRaw(KafkaInstance instance, String method, String path, Object body);

    /** Drops any cached client for the instance (connection details changed). */
    default void evict(Long instanceId) {
    }
}
