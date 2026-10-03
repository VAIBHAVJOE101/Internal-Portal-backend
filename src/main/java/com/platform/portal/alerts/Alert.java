package com.platform.portal.alerts;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Lifecycle: PENDING (condition seen, waiting for the rule's min occurrences / pending duration)
 * → OPEN (firing, notifications and escalation run) → ACKNOWLEDGED (repeats and escalation stop)
 * → RESOLVED (manually, or automatically once the condition stays clear for the grace period).
 */
@Entity
@Table(name = "alert")
public class Alert {

    public enum Severity { CRITICAL, WARNING, INFO }

    public enum Status { PENDING, OPEN, ACKNOWLEDGED, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String dedupeKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type")
    private AlertType type;
    private String source;
    @Enumerated(EnumType.STRING)
    private Severity severity;
    private String title;
    private String message;
    private String resource;
    @Enumerated(EnumType.STRING)
    private Status status;
    private int occurrences;
    private Instant firstSeen;
    private Instant lastSeen;
    private Instant firedAt;
    private Instant clearedSince;
    private Instant lastNotifiedAt;
    private Instant nextNotifyAt;
    private int notificationCount;
    private int escalationLevel;
    private Instant snoozedUntil;
    private String snoozedBy;
    private int reopenCount;
    private String acknowledgedBy;
    private Instant acknowledgedAt;
    private Instant resolvedAt;
    private String resolvedBy;
    private String resolvedReason;

    public boolean isActive() {
        return status != Status.RESOLVED;
    }

    public boolean isSnoozed(Instant now) {
        return snoozedUntil != null && snoozedUntil.isAfter(now);
    }

    public Long getId() { return id; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }
    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getOccurrences() { return occurrences; }
    public void setOccurrences(int occurrences) { this.occurrences = occurrences; }
    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    public Instant getFiredAt() { return firedAt; }
    public void setFiredAt(Instant firedAt) { this.firedAt = firedAt; }
    public Instant getClearedSince() { return clearedSince; }
    public void setClearedSince(Instant clearedSince) { this.clearedSince = clearedSince; }
    public Instant getLastNotifiedAt() { return lastNotifiedAt; }
    public void setLastNotifiedAt(Instant lastNotifiedAt) { this.lastNotifiedAt = lastNotifiedAt; }
    public Instant getNextNotifyAt() { return nextNotifyAt; }
    public void setNextNotifyAt(Instant nextNotifyAt) { this.nextNotifyAt = nextNotifyAt; }
    public int getNotificationCount() { return notificationCount; }
    public void setNotificationCount(int notificationCount) { this.notificationCount = notificationCount; }
    public int getEscalationLevel() { return escalationLevel; }
    public void setEscalationLevel(int escalationLevel) { this.escalationLevel = escalationLevel; }
    public Instant getSnoozedUntil() { return snoozedUntil; }
    public void setSnoozedUntil(Instant snoozedUntil) { this.snoozedUntil = snoozedUntil; }
    public String getSnoozedBy() { return snoozedBy; }
    public void setSnoozedBy(String snoozedBy) { this.snoozedBy = snoozedBy; }
    public int getReopenCount() { return reopenCount; }
    public void setReopenCount(int reopenCount) { this.reopenCount = reopenCount; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getResolvedReason() { return resolvedReason; }
    public void setResolvedReason(String resolvedReason) { this.resolvedReason = resolvedReason; }
}
