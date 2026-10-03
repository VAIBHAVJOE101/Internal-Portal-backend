package com.platform.portal.alerts;

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

import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.PageResult;
import com.platform.portal.common.Strings;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central alerting. Modules call {@link #raise} with a stable dedupe key; repeated raises bump the
 * occurrence counter instead of creating duplicates, and {@link #resolve} closes the alert automatically
 * once the condition clears.
 */
@Service
public class AlertService {

    public static final Set<Alert.Status> ACTIVE = EnumSet.of(Alert.Status.OPEN, Alert.Status.ACKNOWLEDGED);

    private final AlertRepository repository;
    private final AlertStream stream;
    private final AlertNotifier notifier;
    private final AuditService audit;

    public AlertService(AlertRepository repository, AlertStream stream, AlertNotifier notifier, AuditService audit) {
        this.repository = repository;
        this.stream = stream;
        this.notifier = notifier;
        this.audit = audit;
    }

    @Transactional
    public Alert raise(String source, Alert.Severity severity, String dedupeKey, String title, String message, String resource) {
        Instant now = Instant.now();
        Alert alert = repository.findFirstByDedupeKeyAndStatusIn(dedupeKey, ACTIVE).orElse(null);
        boolean isNew = alert == null;
        if (isNew) {
            alert = new Alert();
            alert.setDedupeKey(dedupeKey);
            alert.setSource(source);
            alert.setStatus(Alert.Status.OPEN);
            alert.setOccurrences(1);
            alert.setFirstSeen(now);
        } else {
            alert.setOccurrences(alert.getOccurrences() + 1);
        }
        boolean escalated = !isNew && alert.getSeverity() != severity;
        alert.setSeverity(severity);
        alert.setTitle(Strings.truncate(title, 300));
        alert.setMessage(Strings.truncate(message, 4000));
        alert.setResource(Strings.truncate(resource, 300));
        alert.setLastSeen(now);
        Alert saved = repository.save(alert);
        if (isNew || escalated) {
            stream.publish("alert", AlertDto.of(saved));
            notifier.notifyAsync(saved);
        }
        return saved;
    }

    @Transactional
    public void resolve(String dedupeKey) {
        repository.findFirstByDedupeKeyAndStatusIn(dedupeKey, ACTIVE).ifPresent(this::close);
    }

    /** Resolves every active alert whose key starts with the prefix, except those still present. */
    @Transactional
    public void resolveMissing(String prefix, Set<String> stillActiveKeys) {
        repository.findByDedupeKeyStartingWithAndStatusIn(prefix, ACTIVE).stream()
                .filter(a -> !stillActiveKeys.contains(a.getDedupeKey()))
                .forEach(this::close);
    }

    @Transactional
    public AlertDto acknowledge(Long id) {
        Alert alert = repository.findById(id).orElseThrow(() -> ApiException.notFound("Alert"));
        if (alert.getStatus() == Alert.Status.RESOLVED) {
            throw ApiException.conflict("Alert is already resolved");
        }
        audit.track("ALERT_ACK", "alert", String.valueOf(id), Map.of("title", alert.getTitle()), () -> {
            alert.setStatus(Alert.Status.ACKNOWLEDGED);
            alert.setAcknowledgedBy(CurrentUser.username());
            alert.setAcknowledgedAt(Instant.now());
        });
        stream.publish("alert", AlertDto.of(alert));
        return AlertDto.of(alert);
    }

    @Transactional
    public AlertDto resolveById(Long id) {
        Alert alert = repository.findById(id).orElseThrow(() -> ApiException.notFound("Alert"));
        audit.track("ALERT_RESOLVE", "alert", String.valueOf(id), Map.of("title", alert.getTitle()), () -> close(alert));
        return AlertDto.of(alert);
    }

    @Transactional(readOnly = true)
    public PageResult<AlertDto> search(String status, String severity, String source, String q, int page, int size) {
        Specification<Alert> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if ("ACTIVE".equalsIgnoreCase(status)) {
                p.add(root.get("status").in(ACTIVE));
            } else if (!Strings.isBlank(status)) {
                p.add(cb.equal(root.get("status"), Alert.Status.valueOf(status.toUpperCase())));
            }
            if (!Strings.isBlank(severity)) p.add(cb.equal(root.get("severity"), Alert.Severity.valueOf(severity.toUpperCase())));
            if (!Strings.isBlank(source)) p.add(cb.equal(root.get("source"), source.toUpperCase()));
            if (!Strings.isBlank(q)) {
                String like = "%" + q.toLowerCase() + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like), cb.like(cb.lower(root.get("resource")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var result = repository.findAll(spec, PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "lastSeen")));
        return PageResult.of(result.map(AlertDto::of));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("active", repository.countByStatusIn(ACTIVE));
        summary.put("critical", repository.countByStatusInAndSeverity(ACTIVE, Alert.Severity.CRITICAL));
        summary.put("warning", repository.countByStatusInAndSeverity(ACTIVE, Alert.Severity.WARNING));
        summary.put("info", repository.countByStatusInAndSeverity(ACTIVE, Alert.Severity.INFO));
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

    private void close(Alert alert) {
        alert.setStatus(Alert.Status.RESOLVED);
        alert.setResolvedAt(Instant.now());
        repository.save(alert);
        stream.publish("alert", AlertDto.of(alert));
    }

    public record AlertDto(Long id, String source, Alert.Severity severity, String title, String message, String resource,
                           Alert.Status status, int occurrences, Instant firstSeen, Instant lastSeen,
                           String acknowledgedBy, Instant acknowledgedAt, Instant resolvedAt) {
        static AlertDto of(Alert a) {
            return new AlertDto(a.getId(), a.getSource(), a.getSeverity(), a.getTitle(), a.getMessage(), a.getResource(),
                    a.getStatus(), a.getOccurrences(), a.getFirstSeen(), a.getLastSeen(), a.getAcknowledgedBy(),
                    a.getAcknowledgedAt(), a.getResolvedAt());
        }
    }
}
