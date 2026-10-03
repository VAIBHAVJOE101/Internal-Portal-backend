package com.platform.portal.alerts;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persisted policy overrides for one {@link AlertType}. */
@Entity
@Table(name = "alert_rule")
public class AlertRule {

    @Id
    @Column(name = "alert_type")
    @Enumerated(EnumType.STRING)
    private AlertType type;
    private boolean enabled = true;
    @Enumerated(EnumType.STRING)
    private Alert.Severity severity;
    private int minOccurrences;
    private int pendingSeconds;
    private int repeatMinutes;
    private double backoffMultiplier;
    private int maxRepeatMinutes;
    private int maxNotifications;
    private boolean notifyOnResolve;
    private int resolveGraceSeconds;
    private int staleMinutes;
    private int reopenWindowMinutes;
    private boolean emailEnabled;
    private String emailRecipients;
    private boolean teamsEnabled;
    /** JSON list of {@link AlertType.EscalationStep}. */
    private String escalation;
    /** JSON map of type specific thresholds. */
    private String params;
    private Instant mutedUntil;
    private String muteReason;
    private String updatedBy;
    private Instant updatedAt;

    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Alert.Severity getSeverity() { return severity; }
    public void setSeverity(Alert.Severity severity) { this.severity = severity; }
    public int getMinOccurrences() { return minOccurrences; }
    public void setMinOccurrences(int minOccurrences) { this.minOccurrences = minOccurrences; }
    public int getPendingSeconds() { return pendingSeconds; }
    public void setPendingSeconds(int pendingSeconds) { this.pendingSeconds = pendingSeconds; }
    public int getRepeatMinutes() { return repeatMinutes; }
    public void setRepeatMinutes(int repeatMinutes) { this.repeatMinutes = repeatMinutes; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    public int getMaxRepeatMinutes() { return maxRepeatMinutes; }
    public void setMaxRepeatMinutes(int maxRepeatMinutes) { this.maxRepeatMinutes = maxRepeatMinutes; }
    public int getMaxNotifications() { return maxNotifications; }
    public void setMaxNotifications(int maxNotifications) { this.maxNotifications = maxNotifications; }
    public boolean isNotifyOnResolve() { return notifyOnResolve; }
    public void setNotifyOnResolve(boolean notifyOnResolve) { this.notifyOnResolve = notifyOnResolve; }
    public int getResolveGraceSeconds() { return resolveGraceSeconds; }
    public void setResolveGraceSeconds(int resolveGraceSeconds) { this.resolveGraceSeconds = resolveGraceSeconds; }
    public int getStaleMinutes() { return staleMinutes; }
    public void setStaleMinutes(int staleMinutes) { this.staleMinutes = staleMinutes; }
    public int getReopenWindowMinutes() { return reopenWindowMinutes; }
    public void setReopenWindowMinutes(int reopenWindowMinutes) { this.reopenWindowMinutes = reopenWindowMinutes; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean emailEnabled) { this.emailEnabled = emailEnabled; }
    public String getEmailRecipients() { return emailRecipients; }
    public void setEmailRecipients(String emailRecipients) { this.emailRecipients = emailRecipients; }
    public boolean isTeamsEnabled() { return teamsEnabled; }
    public void setTeamsEnabled(boolean teamsEnabled) { this.teamsEnabled = teamsEnabled; }
    public String getEscalation() { return escalation; }
    public void setEscalation(String escalation) { this.escalation = escalation; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public Instant getMutedUntil() { return mutedUntil; }
    public void setMutedUntil(Instant mutedUntil) { this.mutedUntil = mutedUntil; }
    public String getMuteReason() { return muteReason; }
    public void setMuteReason(String muteReason) { this.muteReason = muteReason; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public interface Repository extends JpaRepository<AlertRule, AlertType> {
    }
}
