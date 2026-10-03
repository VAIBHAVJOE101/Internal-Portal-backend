package com.platform.portal.alerts;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.platform.portal.alerts.AlertNotifier.Kind;
import com.platform.portal.alerts.AlertRuleService.Policy;
import com.platform.portal.alerts.AlertType.EscalationStep;
import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.PageResult;
import com.platform.portal.common.Strings;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central alerting with an Alertmanager / PagerDuty style lifecycle.
 *
 * <ul>
 *   <li><b>Raise</b> – modules call {@link #raise} on every failed check with a stable dedupe key. The alert starts
 *       PENDING and fires once the rule's minimum occurrences and pending duration are met (suppresses flapping).</li>
 *   <li><b>Notify</b> – on firing, then repeats while unacknowledged at increasing intervals
 *       (repeat × backoff^n, capped), optionally limited to N notifications.</li>
 *   <li><b>Escalate</b> – escalation steps run when the alert is still unacknowledged N minutes after firing:
 *       extra recipients / escalation Teams channel and optionally raise severity to CRITICAL.</li>
 *   <li><b>Auto-resolve</b> – modules call {@link #resolve} when the check passes again; the alert resolves after the
 *       condition stayed clear for the grace period, or when it is no longer reported (stale). Pending alerts that
 *       clear are dropped silently. Re-raised alerts within the reopen window reopen instead of duplicating.</li>
 *   <li><b>Operator actions</b> – acknowledge (stops repeats/escalation), snooze (suppresses notifications until a
 *       time), resolve manually. Rules can be disabled or muted for maintenance windows.</li>
 * </ul>
 * Every transition is written to the alert timeline ({@link AlertEvent}).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    public static final Set<Alert.Status> ACTIVE = EnumSet.of(Alert.Status.PENDING, Alert.Status.OPEN, Alert.Status.ACKNOWLEDGED);
    private static final Set<Alert.Status> FIRING = EnumSet.of(Alert.Status.OPEN, Alert.Status.ACKNOWLEDGED);

    private final AlertRepository repository;
    private final AlertEvent.Repository events;
    private final AlertRuleService rules;
    private final AlertStream stream;
    private final AlertNotifier notifier;
    private final AuditService audit;

    public AlertService(AlertRepository repository, AlertEvent.Repository events, AlertRuleService rules, AlertStream stream,
                        AlertNotifier notifier, AuditService audit) {
        this.repository = repository;
        this.events = events;
        this.rules = rules;
        this.stream = stream;
        this.notifier = notifier;
        this.audit = audit;
    }

    // ================================================================== condition reporting (called by modules)

    /**
     * Reports that the condition behind {@code dedupeKey} is present.
     *
     * @param severity severity for this occurrence, or {@code null} to use the rule's severity
     */
    @Transactional
    public Alert raise(AlertType type, String dedupeKey, Alert.Severity severity, String title, String message, String resource) {
        Policy policy = rules.policy(type);
        if (!policy.enabled()) {
            return null; // the engine resolves any leftover active alert of a disabled rule
        }
        Instant now = Instant.now();
        Alert.Severity incoming = severity == null ? policy.severity() : severity;
        Alert alert = repository.findFirstByDedupeKeyAndStatusIn(dedupeKey, ACTIVE).orElse(null);

        if (alert == null) {
            Alert recent = policy.reopenWindowMinutes() <= 0 ? null
                    : repository.findFirstByDedupeKeyAndStatusAndResolvedAtAfterOrderByResolvedAtDesc(dedupeKey, Alert.Status.RESOLVED,
                    now.minus(policy.reopenWindowMinutes(), ChronoUnit.MINUTES)).orElse(null);
            if (recent != null) {
                alert = recent;
                alert.setStatus(Alert.Status.PENDING);
                alert.setReopenCount(alert.getReopenCount() + 1);
                alert.setOccurrences(1);
                alert.setFirstSeen(now);
                alert.setFiredAt(null);
                alert.setResolvedAt(null);
                alert.setResolvedBy(null);
                alert.setResolvedReason(null);
                alert.setAcknowledgedAt(null);
                alert.setAcknowledgedBy(null);
                alert.setEscalationLevel(0);
                alert.setNextNotifyAt(null);
                alert.setSeverity(incoming);
                event(alert, AlertEvent.Kind.REOPENED, "Condition returned within the reopen window (" + policy.reopenWindowMinutes() + " min)", null);
            } else {
                alert = new Alert();
                alert.setDedupeKey(dedupeKey);
                alert.setType(type);
                alert.setSource(type.source());
                alert.setStatus(Alert.Status.PENDING);
                alert.setOccurrences(1);
                alert.setFirstSeen(now);
                alert.setSeverity(incoming);
            }
        } else {
            alert.setOccurrences(alert.getOccurrences() + 1);
            if (alert.getClearedSince() != null) {
                alert.setClearedSince(null);
                event(alert, AlertEvent.Kind.CONDITION_RETURNED, "Condition detected again before the resolve grace period ended", null);
            }
        }
        boolean isNew = alert.getId() == null;
        alert.setTitle(Strings.truncate(title, 300));
        alert.setMessage(Strings.truncate(message, 4000));
        alert.setResource(Strings.truncate(resource, 300));
        alert.setLastSeen(now);
        alert.setClearedSince(null);
        Alert.Severity previous = alert.getSeverity();
        if (incoming.ordinal() < previous.ordinal()) { // severity only goes up while active
            alert.setSeverity(incoming);
        }
        alert = repository.save(alert);
        if (isNew) {
            event(alert, AlertEvent.Kind.RAISED, describePending(policy), null);
        }
        if (alert.getSeverity() != previous) {
            event(alert, AlertEvent.Kind.SEVERITY_CHANGED, previous + " → " + alert.getSeverity(), null);
            if (alert.getStatus() == Alert.Status.OPEN) {
                notify(alert, policy, Kind.ESCALATION, List.of(), policy.teamsEnabled(), false, now);
            }
        }
        if (alert.getStatus() == Alert.Status.PENDING && readyToFire(alert, policy, now)) {
            fire(alert, policy, now);
        }
        stream.publish("alert", AlertDto.of(alert, false));
        return alert;
    }

    /** Reports that the condition behind {@code dedupeKey} is gone (health check passed). */
    @Transactional
    public void resolve(String dedupeKey) {
        repository.findFirstByDedupeKeyAndStatusIn(dedupeKey, ACTIVE).ifPresent(a -> conditionCleared(a, Instant.now()));
    }

    /** Clears every active alert whose key starts with the prefix and is not in {@code stillActiveKeys}. */
    @Transactional
    public void resolveMissing(String prefix, Set<String> stillActiveKeys) {
        Instant now = Instant.now();
        repository.findByDedupeKeyStartingWithAndStatusIn(prefix, ACTIVE).stream()
                .filter(a -> !stillActiveKeys.contains(a.getDedupeKey()))
                .forEach(a -> conditionCleared(a, now));
    }

    private void conditionCleared(Alert alert, Instant now) {
        Policy policy = rules.policy(alert.getType());
        if (alert.getStatus() == Alert.Status.PENDING) {
            close(alert, policy, "Condition cleared before the alert fired", "system", now, false);
        } else if (policy.resolveGraceSeconds() <= 0) {
            close(alert, policy, "Auto-resolved: health check passed", "system", now, true);
        } else if (alert.getClearedSince() == null) {
            alert.setClearedSince(now);
            event(alert, AlertEvent.Kind.CONDITION_CLEARED,
                    "Health check passed – resolving if it stays clear for " + human(policy.resolveGraceSeconds()), null);
            stream.publish("alert", AlertDto.of(alert, false));
        }
    }

    // ================================================================== engine tick

    /** Periodic evaluation: firing, auto-resolve, snooze expiry, repeat notifications and escalation. */
    @Transactional
    public void evaluate() {
        Instant now = Instant.now();
        for (Alert alert : repository.findByStatusIn(ACTIVE)) {
            try {
                evaluate(alert, rules.policy(alert.getType()), now);
            } catch (RuntimeException e) {
                log.warn("Alert evaluation failed for {}: {}", alert.getId(), e.getMessage());
            }
        }
    }

    private void evaluate(Alert alert, Policy policy, Instant now) {
        if (!policy.enabled()) {
            close(alert, policy, "Rule disabled", "system", now, false);
            return;
        }
        if (alert.getClearedSince() != null
                && !now.isBefore(alert.getClearedSince().plusSeconds(policy.resolveGraceSeconds()))) {
            close(alert, policy, "Auto-resolved: condition clear for " + human(policy.resolveGraceSeconds()), "system", now, true);
            return;
        }
        if (policy.staleMinutes() > 0 && alert.getClearedSince() == null
                && alert.getLastSeen().plus(policy.staleMinutes(), ChronoUnit.MINUTES).isBefore(now)) {
            close(alert, policy, "Auto-resolved: not reported for " + policy.staleMinutes() + " min", "system", now, true);
            return;
        }
        if (alert.getStatus() == Alert.Status.PENDING) {
            if (readyToFire(alert, policy, now)) fire(alert, policy, now);
            return;
        }
        if (alert.getSnoozedUntil() != null && !alert.getSnoozedUntil().isAfter(now)) {
            alert.setSnoozedUntil(null);
            alert.setNextNotifyAt(now);
            event(alert, AlertEvent.Kind.UNSNOOZED, "Snooze expired", null);
        }
        if (alert.getStatus() != Alert.Status.OPEN || alert.isSnoozed(now) || alert.getClearedSince() != null) {
            return; // acknowledged, snoozed or recovering: no repeats or escalation
        }
        // escalation (one step per tick)
        List<EscalationStep> steps = policy.escalation();
        if (alert.getEscalationLevel() < steps.size() && alert.getFiredAt() != null) {
            EscalationStep step = steps.get(alert.getEscalationLevel());
            if (!now.isBefore(alert.getFiredAt().plus(step.afterMinutes(), ChronoUnit.MINUTES))) {
                alert.setEscalationLevel(alert.getEscalationLevel() + 1);
                if (step.raiseToCritical() && alert.getSeverity() != Alert.Severity.CRITICAL) {
                    event(alert, AlertEvent.Kind.SEVERITY_CHANGED, alert.getSeverity() + " → CRITICAL (escalation)", null);
                    alert.setSeverity(Alert.Severity.CRITICAL);
                }
                event(alert, AlertEvent.Kind.ESCALATED, "Level " + alert.getEscalationLevel() + ": unacknowledged for "
                        + step.afterMinutes() + " min" + (step.emails().isEmpty() ? "" : " → " + String.join(", ", step.emails())), null);
                notify(alert, policy, Kind.ESCALATION, step.emails(), step.teams(), true, now);
                stream.publish("alert", AlertDto.of(alert, false));
                return;
            }
        }
        // repeat notifications with backoff
        if (policy.repeatMinutes() > 0 && alert.getNextNotifyAt() != null && !now.isBefore(alert.getNextNotifyAt())) {
            if (policy.maxNotifications() > 0 && alert.getNotificationCount() >= policy.maxNotifications()) {
                alert.setNextNotifyAt(null);
                event(alert, AlertEvent.Kind.SUPPRESSED, "Notification limit (" + policy.maxNotifications() + ") reached", null);
                return;
            }
            notify(alert, policy, Kind.REPEAT, List.of(), policy.teamsEnabled(), false, now);
        }
    }

    private static boolean readyToFire(Alert alert, Policy policy, Instant now) {
        return alert.getOccurrences() >= policy.minOccurrences()
                && !now.isBefore(alert.getFirstSeen().plusSeconds(policy.pendingSeconds()));
    }

    private void fire(Alert alert, Policy policy, Instant now) {
        alert.setStatus(Alert.Status.OPEN);
        alert.setFiredAt(now);
        event(alert, AlertEvent.Kind.FIRED, "Firing after " + alert.getOccurrences() + " occurrence(s) over "
                + human(Duration.between(alert.getFirstSeen(), now).toSeconds()), null);
        notify(alert, policy, Kind.FIRING, List.of(), policy.teamsEnabled(), false, now);
        stream.publish("alert", AlertDto.of(alert, true));
    }

    private void close(Alert alert, Policy policy, String reason, String by, Instant now, boolean notifyResolved) {
        boolean wasFiring = FIRING.contains(alert.getStatus());
        alert.setStatus(Alert.Status.RESOLVED);
        alert.setResolvedAt(now);
        alert.setResolvedBy(by);
        alert.setResolvedReason(reason);
        alert.setClearedSince(null);
        alert.setNextNotifyAt(null);
        alert.setSnoozedUntil(null);
        repository.save(alert);
        event(alert, AlertEvent.Kind.RESOLVED, reason, "system".equals(by) ? null : by);
        if (notifyResolved && wasFiring && policy.notifyOnResolve() && alert.getNotificationCount() > 0) {
            notify(alert, policy, Kind.RESOLVED, List.of(), policy.teamsEnabled(), false, now);
        }
        stream.publish("alert", AlertDto.of(alert, false));
    }

    /** Sends (or suppresses) a notification and updates the repeat schedule. */
    private void notify(Alert alert, Policy policy, Kind kind, List<String> extraEmails, boolean teams, boolean escalationChannel, Instant now) {
        if (policy.isMuted(now)) {
            event(alert, AlertEvent.Kind.SUPPRESSED, "Rule muted until " + policy.mutedUntil()
                    + (policy.muteReason() == null ? "" : " (" + policy.muteReason() + ")"), null);
        } else if (kind != Kind.RESOLVED && alert.isSnoozed(now)) {
            event(alert, AlertEvent.Kind.SUPPRESSED, "Snoozed until " + alert.getSnoozedUntil(), null);
        } else {
            if (kind != Kind.RESOLVED) {
                alert.setNotificationCount(alert.getNotificationCount() + 1);
                alert.setLastNotifiedAt(now);
            }
            repository.save(alert);
            notifier.dispatch(AlertNotifier.Message.of(alert), policy, kind, extraEmails, teams, escalationChannel);
        }
        if (kind == Kind.FIRING || kind == Kind.REPEAT) {
            alert.setNextNotifyAt(policy.repeatMinutes() <= 0 ? null
                    : now.plus(policy.repeatDelayMinutes(Math.max(1, alert.getNotificationCount())), ChronoUnit.MINUTES));
        }
    }

    // ================================================================== operator actions

    @Transactional
    public AlertDto acknowledge(Long id) {
        Alert alert = find(id);
        if (alert.getStatus() != Alert.Status.OPEN) {
            throw ApiException.conflict("Only firing alerts can be acknowledged (status is " + alert.getStatus() + ")");
        }
        audit.track("ALERT_ACK", "alert", String.valueOf(id), Map.of("title", alert.getTitle()), () -> {
            alert.setStatus(Alert.Status.ACKNOWLEDGED);
            alert.setAcknowledgedBy(CurrentUser.username());
            alert.setAcknowledgedAt(Instant.now());
            alert.setNextNotifyAt(null);
        });
        event(alert, AlertEvent.Kind.ACKNOWLEDGED, "Repeat notifications and escalation stopped", CurrentUser.username());
        stream.publish("alert", AlertDto.of(alert, false));
        return AlertDto.of(alert, false);
    }

    @Transactional
    public AlertDto snooze(Long id, int minutes) {
        if (minutes < 5 || minutes > 7 * 24 * 60) {
            throw ApiException.badRequest("Snooze between 5 minutes and 7 days");
        }
        Alert alert = find(id);
        if (!alert.isActive()) throw ApiException.conflict("Alert is resolved");
        Instant until = Instant.now().plus(minutes, ChronoUnit.MINUTES);
        audit.track("ALERT_SNOOZE", "alert", String.valueOf(id), Map.of("until", until.toString()), () -> {
            alert.setSnoozedUntil(until);
            alert.setSnoozedBy(CurrentUser.username());
        });
        event(alert, AlertEvent.Kind.SNOOZED, "Notifications suppressed for " + human(minutes * 60L), CurrentUser.username());
        stream.publish("alert", AlertDto.of(alert, false));
        return AlertDto.of(alert, false);
    }

    @Transactional
    public AlertDto unsnooze(Long id) {
        Alert alert = find(id);
        audit.track("ALERT_UNSNOOZE", "alert", String.valueOf(id), Map.of(), () -> {
            alert.setSnoozedUntil(null);
            alert.setNextNotifyAt(Instant.now());
        });
        event(alert, AlertEvent.Kind.UNSNOOZED, "Snooze cancelled", CurrentUser.username());
        return AlertDto.of(alert, false);
    }

    @Transactional
    public AlertDto resolveById(Long id) {
        Alert alert = find(id);
        if (!alert.isActive()) throw ApiException.conflict("Alert is already resolved");
        String user = CurrentUser.username();
        audit.track("ALERT_RESOLVE", "alert", String.valueOf(id), Map.of("title", alert.getTitle()),
                () -> close(alert, rules.policy(alert.getType()), "Resolved manually by " + user, user, Instant.now(), true));
        return AlertDto.of(alert, false);
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public PageResult<AlertDto> search(String status, String severity, String source, String type, String q, int page, int size) {
        Specification<Alert> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if ("ACTIVE".equalsIgnoreCase(status)) {
                p.add(root.get("status").in(ACTIVE));
            } else if ("FIRING".equalsIgnoreCase(status)) {
                p.add(root.get("status").in(FIRING));
            } else if (!Strings.isBlank(status)) {
                p.add(cb.equal(root.get("status"), Alert.Status.valueOf(status.toUpperCase())));
            }
            if (!Strings.isBlank(severity)) p.add(cb.equal(root.get("severity"), Alert.Severity.valueOf(severity.toUpperCase())));
            if (!Strings.isBlank(source)) p.add(cb.equal(root.get("source"), source.toUpperCase()));
            if (!Strings.isBlank(type)) p.add(cb.equal(root.get("type"), AlertType.valueOf(type.toUpperCase())));
            if (!Strings.isBlank(q)) {
                String like = "%" + q.toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like), cb.like(cb.lower(root.get("resource")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var result = repository.findAll(spec, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "lastSeen")));
        return PageResult.of(result.map(a -> AlertDto.of(a, false)));
    }

    @Transactional(readOnly = true)
    public AlertDto get(Long id) {
        return AlertDto.of(find(id), false);
    }

    @Transactional(readOnly = true)
    public List<AlertEvent> events(Long id) {
        return events.findByAlertIdOrderByTsAscIdAsc(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("active", repository.countByStatusIn(FIRING));
        summary.put("pending", repository.countByStatusIn(EnumSet.of(Alert.Status.PENDING)));
        summary.put("critical", repository.countByStatusInAndSeverity(FIRING, Alert.Severity.CRITICAL));
        summary.put("warning", repository.countByStatusInAndSeverity(FIRING, Alert.Severity.WARNING));
        summary.put("info", repository.countByStatusInAndSeverity(FIRING, Alert.Severity.INFO));
        return summary;
    }

    /** Alerts opened per day for the last {@code days} days, split by severity, for trend charts. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trend(int days) {
        Instant since = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(days - 1L, ChronoUnit.DAYS);
        Map<String, Map<Alert.Severity, Long>> byDay = repository.findByFirstSeenAfter(since).stream()
                .collect(Collectors.groupingBy(a -> a.getFirstSeen().truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10),
                        TreeMap::new, Collectors.groupingBy(Alert::getSeverity, Collectors.counting())));
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String day = since.plus(i, ChronoUnit.DAYS).toString().substring(0, 10);
            Map<Alert.Severity, Long> counts = byDay.getOrDefault(day, Map.of());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", day);
            point.put("critical", counts.getOrDefault(Alert.Severity.CRITICAL, 0L));
            point.put("warning", counts.getOrDefault(Alert.Severity.WARNING, 0L));
            point.put("info", counts.getOrDefault(Alert.Severity.INFO, 0L));
            points.add(point);
        }
        return points;
    }

    // ================================================================== helpers

    private Alert find(Long id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Alert"));
    }

    private void event(Alert alert, AlertEvent.Kind kind, String message, String actor) {
        if (alert.getId() == null) {
            repository.save(alert);
        }
        events.save(new AlertEvent(alert.getId(), kind, null, null, message, actor));
    }

    private static String describePending(Policy p) {
        if (p.minOccurrences() <= 1 && p.pendingSeconds() <= 0) return "Condition detected – firing immediately";
        return "Condition detected – pending until " + p.minOccurrences() + " occurrence(s) over at least " + human(p.pendingSeconds());
    }

    static String human(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + " min";
        if (seconds < 86_400) return String.format("%.1f h", seconds / 3600.0).replace(".0 ", " ");
        return String.format("%.1f d", seconds / 86_400.0).replace(".0 ", " ");
    }

    public record AlertDto(Long id, AlertType type, String typeLabel, String source, Alert.Severity severity, String title, String message,
                           String resource, Alert.Status status, int occurrences, Instant firstSeen, Instant lastSeen, Instant firedAt,
                           Instant clearedSince, Instant lastNotifiedAt, Instant nextNotifyAt, int notificationCount,
                           int escalationLevel, Instant snoozedUntil, String snoozedBy, int reopenCount, String acknowledgedBy,
                           Instant acknowledgedAt, Instant resolvedAt, String resolvedBy, String resolvedReason, boolean justFired) {
        static AlertDto of(Alert a, boolean justFired) {
            return new AlertDto(a.getId(), a.getType(), a.getType() == null ? null : a.getType().label(), a.getSource(), a.getSeverity(),
                    a.getTitle(), a.getMessage(), a.getResource(), a.getStatus(), a.getOccurrences(), a.getFirstSeen(), a.getLastSeen(),
                    a.getFiredAt(), a.getClearedSince(), a.getLastNotifiedAt(), a.getNextNotifyAt(), a.getNotificationCount(),
                    a.getEscalationLevel(), a.getSnoozedUntil(), a.getSnoozedBy(), a.getReopenCount(), a.getAcknowledgedBy(),
                    a.getAcknowledgedAt(), a.getResolvedAt(), a.getResolvedBy(), a.getResolvedReason(), justFired);
        }
    }
}
