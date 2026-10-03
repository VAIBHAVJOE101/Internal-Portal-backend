package com.platform.portal.kafka;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Entity
@Table(name = "kafka_health_snapshot")
public class KafkaHealthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long instanceId;
    private String instanceName;
    private Instant ts;
    private String status;
    private Integer brokersOnline;
    private Integer brokersTotal;
    private Integer topics;
    private Integer underReplicated;
    private Integer connectorsTotal;
    private Integer connectorsFailed;
    private Long maxLag;
    private String message;

    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getInstanceName() { return instanceName; }
    public void setInstanceName(String instanceName) { this.instanceName = instanceName; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getBrokersOnline() { return brokersOnline; }
    public void setBrokersOnline(Integer brokersOnline) { this.brokersOnline = brokersOnline; }
    public Integer getBrokersTotal() { return brokersTotal; }
    public void setBrokersTotal(Integer brokersTotal) { this.brokersTotal = brokersTotal; }
    public Integer getTopics() { return topics; }
    public void setTopics(Integer topics) { this.topics = topics; }
    public Integer getUnderReplicated() { return underReplicated; }
    public void setUnderReplicated(Integer underReplicated) { this.underReplicated = underReplicated; }
    public Integer getConnectorsTotal() { return connectorsTotal; }
    public void setConnectorsTotal(Integer connectorsTotal) { this.connectorsTotal = connectorsTotal; }
    public Integer getConnectorsFailed() { return connectorsFailed; }
    public void setConnectorsFailed(Integer connectorsFailed) { this.connectorsFailed = connectorsFailed; }
    public Long getMaxLag() { return maxLag; }
    public void setMaxLag(Long maxLag) { this.maxLag = maxLag; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public interface Repository extends JpaRepository<KafkaHealthSnapshot, Long> {

        Optional<KafkaHealthSnapshot> findFirstByInstanceIdOrderByTsDesc(Long instanceId);

        List<KafkaHealthSnapshot> findByInstanceIdAndTsAfterOrderByTsAsc(Long instanceId, Instant since);

        @Modifying
        @Transactional
        @Query("delete from KafkaHealthSnapshot s where s.ts < :before")
        int deleteOlderThan(Instant before);
    }
}
