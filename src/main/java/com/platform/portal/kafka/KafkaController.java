package com.platform.portal.kafka;

import java.util.List;
import java.util.Map;

import com.platform.portal.kafka.KafkaModels.AddPartitionsRequest;
import com.platform.portal.kafka.KafkaModels.AlterConfigsRequest;
import com.platform.portal.kafka.KafkaModels.BrokerInfo;
import com.platform.portal.kafka.KafkaModels.ClusterOverview;
import com.platform.portal.kafka.KafkaModels.Connector;
import com.platform.portal.kafka.KafkaModels.ConnectorRequest;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupDetail;
import com.platform.portal.kafka.KafkaModels.ConsumerGroupSummary;
import com.platform.portal.kafka.KafkaModels.CreateTopicRequest;
import com.platform.portal.kafka.KafkaModels.PurgeRequest;
import com.platform.portal.kafka.KafkaModels.RawRequest;
import com.platform.portal.kafka.KafkaModels.RawResponse;
import com.platform.portal.kafka.KafkaModels.ResetOffsetsRequest;
import com.platform.portal.kafka.KafkaModels.TopicDetail;
import com.platform.portal.kafka.KafkaModels.TopicSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kafka/instances")
public class KafkaController {

    private final KafkaService kafka;

    public KafkaController(KafkaService kafka) {
        this.kafka = kafka;
    }

    @GetMapping
    public List<KafkaService.InstanceCard> instances() {
        return kafka.instances();
    }

    @GetMapping("/{id}/overview")
    public ClusterOverview overview(@PathVariable Long id) {
        return kafka.overview(id);
    }

    @GetMapping("/{id}/health-history")
    public List<KafkaHealthSnapshot> history(@PathVariable Long id, @RequestParam(defaultValue = "24") int hours) {
        return kafka.history(id, Math.min(Math.max(hours, 1), 168));
    }

    @GetMapping("/{id}/brokers")
    public List<BrokerInfo> brokers(@PathVariable Long id) {
        return kafka.brokers(id);
    }

    // ---------------------------------------------------------------- topics

    @GetMapping("/{id}/topics")
    public List<TopicSummary> topics(@PathVariable Long id) {
        return kafka.topics(id);
    }

    @GetMapping("/{id}/topics/{topic}")
    public TopicDetail topic(@PathVariable Long id, @PathVariable String topic) {
        return kafka.topic(id, topic);
    }

    @PostMapping("/{id}/topics")
    @ResponseStatus(HttpStatus.CREATED)
    public TopicDetail createTopic(@PathVariable Long id, @Valid @RequestBody CreateTopicRequest request) {
        return kafka.createTopic(id, request);
    }

    @PutMapping("/{id}/topics/{topic}/configs")
    public TopicDetail alterConfigs(@PathVariable Long id, @PathVariable String topic, @RequestBody AlterConfigsRequest request) {
        return kafka.alterTopicConfigs(id, topic, request);
    }

    @PostMapping("/{id}/topics/{topic}/partitions")
    public TopicDetail addPartitions(@PathVariable Long id, @PathVariable String topic, @Valid @RequestBody AddPartitionsRequest request) {
        return kafka.addPartitions(id, topic, request.totalCount());
    }

    @PostMapping("/{id}/topics/{topic}/purge")
    public ResponseEntity<Void> purge(@PathVariable Long id, @PathVariable String topic, @RequestBody(required = false) PurgeRequest request) {
        kafka.purgeTopic(id, topic, request == null ? null : request.beforeOffsets());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/topics/{topic}")
    public ResponseEntity<Void> deleteTopic(@PathVariable Long id, @PathVariable String topic) {
        kafka.deleteTopic(id, topic);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- consumer groups

    @GetMapping("/{id}/groups")
    public List<ConsumerGroupSummary> groups(@PathVariable Long id) {
        return kafka.consumerGroups(id);
    }

    @GetMapping("/{id}/groups/{group}")
    public ConsumerGroupDetail group(@PathVariable Long id, @PathVariable String group) {
        return kafka.consumerGroup(id, group);
    }

    @PostMapping("/{id}/groups/{group}/reset-offsets")
    public ConsumerGroupDetail resetOffsets(@PathVariable Long id, @PathVariable String group, @Valid @RequestBody ResetOffsetsRequest request) {
        return kafka.resetOffsets(id, group, request);
    }

    @DeleteMapping("/{id}/groups/{group}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id, @PathVariable String group) {
        kafka.deleteConsumerGroup(id, group);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- Kafka Connect

    @GetMapping("/{id}/connectors")
    public List<Connector> connectors(@PathVariable Long id) {
        return kafka.connectors(id);
    }

    @GetMapping("/{id}/connectors/{name}")
    public Connector connector(@PathVariable Long id, @PathVariable String name) {
        return kafka.connector(id, name);
    }

    @PostMapping("/{id}/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public Connector createConnector(@PathVariable Long id, @Valid @RequestBody ConnectorRequest request) {
        return kafka.createConnector(id, request.name(), request.config());
    }

    @PutMapping("/{id}/connectors/{name}/config")
    public Connector updateConnector(@PathVariable Long id, @PathVariable String name, @RequestBody Map<String, String> config) {
        return kafka.updateConnectorConfig(id, name, config);
    }

    @DeleteMapping("/{id}/connectors/{name}")
    public ResponseEntity<Void> deleteConnector(@PathVariable Long id, @PathVariable String name) {
        kafka.deleteConnector(id, name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/connectors/{name}/restart")
    public Connector restart(@PathVariable Long id, @PathVariable String name,
                             @RequestParam(defaultValue = "true") boolean includeTasks,
                             @RequestParam(defaultValue = "false") boolean onlyFailed) {
        return kafka.restartConnector(id, name, includeTasks, onlyFailed);
    }

    @PostMapping("/{id}/connectors/{name}/tasks/{task}/restart")
    public Connector restartTask(@PathVariable Long id, @PathVariable String name, @PathVariable int task) {
        return kafka.restartTask(id, name, task);
    }

    @PostMapping("/{id}/connectors/{name}/pause")
    public Connector pause(@PathVariable Long id, @PathVariable String name) {
        return kafka.pause(id, name);
    }

    @PostMapping("/{id}/connectors/{name}/resume")
    public Connector resume(@PathVariable Long id, @PathVariable String name) {
        return kafka.resume(id, name);
    }

    @GetMapping("/{id}/connector-plugins")
    public List<Map<String, Object>> plugins(@PathVariable Long id) {
        return kafka.plugins(id);
    }

    @PostMapping("/{id}/connect/raw")
    public RawResponse raw(@PathVariable Long id, @Valid @RequestBody RawRequest request) {
        return kafka.raw(id, request.method(), request.path(), request.body());
    }
}
