package com.platform.portal.alerts;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

/** Timeline entry of an alert: state changes, notifications and operator actions. */
@Entity
@Table(name = "alert_event")
public class AlertEvent {

    public enum Kind {
        RAISED, FIRED, REOPENED, SEVERITY_CHANGED, CONDITION_CLEARED, CONDITION_RETURNED, NOTIFIED, NOTIFY_FAILED,
        NOTIFY_SKIPPED, SUPPRESSED, ESCALATED, ACKNOWLEDGED, SNOOZED, UNSNOOZED, RESOLVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long alertId;
    private Instant ts;
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Kind kind;
    private String channel;
    private Boolean success;
    private String message;
    private String actor;

    public AlertEvent() {
    }

    public AlertEvent(Long alertId, Kind kind, String channel, Boolean success, String message, String actor) {
        this.alertId = alertId;
        this.ts = Instant.now();
        this.kind = kind;
        this.channel = channel;
        this.success = success;
        this.message = message == null || message.length() <= 2000 ? message : message.substring(0, 1997) + "...";
        this.actor = actor;
    }

    public Long getId() { return id; }
    public Long getAlertId() { return alertId; }
    public Instant getTs() { return ts; }
    public Kind getKind() { return kind; }
    public String getChannel() { return channel; }
    public Boolean getSuccess() { return success; }
    public String getMessage() { return message; }
    public String getActor() { return actor; }

    public interface Repository extends JpaRepository<AlertEvent, Long> {
        List<AlertEvent> findByAlertIdOrderByTsAscIdAsc(Long alertId);
    }
}
