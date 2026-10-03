package com.platform.portal.connectivity;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Entity
@Table(name = "connectivity_result")
public class ConnectivityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long targetId;
    private Instant ts;
    @Enumerated(EnumType.STRING)
    private ConnectivityTarget.TestType testType;
    private String targetDesc;
    private boolean success;
    private Long latencyMs;
    private String message;
    private String details;
    private String triggeredBy;

    public Long getId() { return id; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public ConnectivityTarget.TestType getTestType() { return testType; }
    public void setTestType(ConnectivityTarget.TestType testType) { this.testType = testType; }
    public String getTargetDesc() { return targetDesc; }
    public void setTargetDesc(String targetDesc) { this.targetDesc = targetDesc; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public interface Repository extends JpaRepository<ConnectivityResult, Long> {

        List<ConnectivityResult> findByTargetIdOrderByTsDesc(Long targetId, Pageable pageable);

        List<ConnectivityResult> findByTargetIdAndTsAfterOrderByTsAsc(Long targetId, Instant since);

        List<ConnectivityResult> findByTargetIdIsNullOrderByTsDesc(Pageable pageable);

        @Modifying
        @Transactional
        @Query("delete from ConnectivityResult r where r.ts < :before")
        int deleteOlderThan(Instant before);
    }

    public interface TargetRepository extends JpaRepository<ConnectivityTarget, Long> {

        List<ConnectivityTarget> findAllByOrderByNameAsc();
    }
}
