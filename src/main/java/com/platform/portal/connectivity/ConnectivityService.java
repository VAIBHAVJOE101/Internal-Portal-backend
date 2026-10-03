package com.platform.portal.connectivity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.platform.portal.alerts.Alert;
import com.platform.portal.alerts.AlertService;
import com.platform.portal.alerts.AlertType;
import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.Json;
import com.platform.portal.common.Strings;
import com.platform.portal.connectivity.ConnectivityProbe.Outcome;
import com.platform.portal.connectivity.ConnectivityProbe.Spec;
import com.platform.portal.connectivity.ConnectivityTarget.ScheduleType;
import com.platform.portal.connectivity.ConnectivityTarget.TestType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectivityService {

    private final ConnectivityResult.TargetRepository targets;
    private final ConnectivityResult.Repository results;
    private final ConnectivityProbe probe;
    private final AlertService alerts;
    private final AuditService audit;
    private final Json json;
    private final ApplicationEventPublisher events;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ConnectivityService(ConnectivityResult.TargetRepository targets, ConnectivityResult.Repository results,
                               ConnectivityProbe probe, AlertService alerts, AuditService audit, Json json,
                               ApplicationEventPublisher events) {
        this.targets = targets;
        this.results = results;
        this.probe = probe;
        this.alerts = alerts;
        this.audit = audit;
        this.json = json;
        this.events = events;
    }

    // ---------------------------------------------------------------- DTOs

    public record TargetRequest(@NotBlank @Size(max = 120) String name, @NotNull TestType testType, String host,
                                @Min(1) @Max(65535) Integer port, String url, String httpMethod, Integer expectedStatus,
                                @Min(100) @Max(60000) Integer timeoutMs, ScheduleType scheduleType,
                                @Min(30) @Max(86400) Integer intervalSeconds, String cron, Boolean enabled,
                                @Min(1) @Max(100) Integer failureThreshold, String tags) {
    }

    public record AdhocRequest(@NotNull TestType testType, String host, Integer port, String url, String httpMethod,
                               Integer expectedStatus, Integer timeoutMs) {
    }

    public record TargetDto(Long id, String name, TestType testType, String host, Integer port, String url, String httpMethod,
                            Integer expectedStatus, int timeoutMs, ScheduleType scheduleType, Integer intervalSeconds, String cron,
                            boolean enabled, int failureThreshold, int consecutiveFailures, String lastStatus, Instant lastRunAt,
                            Long lastLatencyMs, List<String> tags, Instant nextRunAt, Double uptime24h, Long avgLatency24h,
                            String description) {
    }

    public record ResultDto(Long id, Long targetId, Instant ts, TestType testType, String target, boolean success, Long latencyMs,
                            String message, Object details, String triggeredBy) {
    }

    // ---------------------------------------------------------------- targets

    @Transactional(readOnly = true)
    public List<TargetDto> list() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        return targets.findAllByOrderByNameAsc().stream().map(t -> toDto(t, results.findByTargetIdAndTsAfterOrderByTsAsc(t.getId(), since))).toList();
    }

    @Transactional(readOnly = true)
    public TargetDto get(Long id) {
        ConnectivityTarget t = target(id);
        return toDto(t, results.findByTargetIdAndTsAfterOrderByTsAsc(id, Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    @Transactional
    public TargetDto create(TargetRequest request) {
        ConnectivityTarget t = new ConnectivityTarget();
        apply(t, request);
        t.setCreatedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        audit.track("CONNECTIVITY_TARGET_CREATE", "connectivity-target", t.getName(), Map.of("target", describe(t),
                "schedule", scheduleText(t)), () -> targets.save(t));
        events.publishEvent(new TargetChangedEvent(t.getId(), false));
        return get(t.getId());
    }

    @Transactional
    public TargetDto update(Long id, TargetRequest request) {
        ConnectivityTarget t = target(id);
        Map<String, Object> before = Map.of("target", describe(t), "schedule", scheduleText(t), "enabled", t.isEnabled());
        apply(t, request);
        t.setUpdatedAt(Instant.now());
        audit.track("CONNECTIVITY_TARGET_UPDATE", "connectivity-target", t.getName(),
                Map.of("before", before, "after", Map.of("target", describe(t), "schedule", scheduleText(t), "enabled", t.isEnabled())),
                () -> targets.save(t));
        events.publishEvent(new TargetChangedEvent(id, false));
        if (!t.isEnabled() || t.getScheduleType() == ScheduleType.NONE) {
            alerts.resolve(alertKey(id));
        }
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        ConnectivityTarget t = target(id);
        audit.track("CONNECTIVITY_TARGET_DELETE", "connectivity-target", t.getName(), Map.of("target", describe(t)),
                () -> targets.delete(t));
        events.publishEvent(new TargetChangedEvent(id, true));
        alerts.resolve(alertKey(id));
    }

    @Transactional(readOnly = true)
    public List<ResultDto> history(Long id, int limit) {
        return results.findByTargetIdOrderByTsDesc(id, PageRequest.of(0, Math.min(limit, 500))).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ResultDto> adhocHistory(int limit) {
        return results.findByTargetIdIsNullOrderByTsDesc(PageRequest.of(0, Math.min(limit, 200))).stream().map(this::toDto).toList();
    }

    // ---------------------------------------------------------------- execution

    public ResultDto runAdhoc(AdhocRequest r) {
        Spec spec = new Spec(r.testType(), Strings.trimToNull(r.host()), r.port(), Strings.trimToNull(r.url()), r.httpMethod(),
                r.expectedStatus(), r.timeoutMs() == null ? 5000 : Math.min(Math.max(r.timeoutMs(), 100), 60000));
        Outcome outcome = probe.run(spec);
        ConnectivityResult saved = save(null, spec, outcome, CurrentUser.username());
        audit.record("CONNECTIVITY_TEST", "connectivity-adhoc", spec.describe(), Map.of("success", outcome.success()), true, null);
        return toDto(saved);
    }

    public ResultDto runNow(Long id) {
        return execute(id, CurrentUser.username());
    }

    public List<ResultDto> runAll() {
        String user = CurrentUser.username();
        List<Future<ResultDto>> futures = targets.findAllByOrderByNameAsc().stream()
                .filter(ConnectivityTarget::isEnabled)
                .map(t -> executor.submit(() -> execute(t.getId(), user)))
                .toList();
        return futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return null;
            }
        }).filter(r -> r != null).toList();
    }

    /** Runs a saved target, records the result and maintains its alert state. */
    public ResultDto execute(Long id, String triggeredBy) {
        ConnectivityTarget t = targets.findById(id).orElse(null);
        if (t == null) {
            return null;
        }
        Spec spec = spec(t);
        Outcome outcome = probe.run(spec);
        ConnectivityResult saved = save(t.getId(), spec, outcome, triggeredBy);
        t.setLastRunAt(saved.getTs());
        t.setLastLatencyMs(outcome.latencyMs());
        t.setLastStatus(outcome.success() ? "UP" : "DOWN");
        t.setConsecutiveFailures(outcome.success() ? 0 : t.getConsecutiveFailures() + 1);
        targets.save(t);
        if (outcome.success()) {
            alerts.resolve(alertKey(id));
        } else if (t.getConsecutiveFailures() >= t.getFailureThreshold()) {
            alerts.raise(AlertType.CONNECTIVITY_FAILURE, alertKey(id), null,
                    "Connectivity check '" + t.getName() + "' failing (" + t.getConsecutiveFailures() + "x)",
                    outcome.message(), spec.describe());
        }
        return toDto(saved);
    }

    @Transactional
    public void pruneResults() {
        results.deleteOlderThan(Instant.now().minus(14, ChronoUnit.DAYS));
    }

    public List<ConnectivityTarget> scheduledTargets() {
        return targets.findAllByOrderByNameAsc().stream()
                .filter(t -> t.isEnabled() && t.getScheduleType() != ScheduleType.NONE).toList();
    }

    public ConnectivityTarget find(Long id) {
        return targets.findById(id).orElse(null);
    }

    // ---------------------------------------------------------------- helpers

    private ConnectivityResult save(Long targetId, Spec spec, Outcome outcome, String user) {
        ConnectivityResult r = new ConnectivityResult();
        r.setTargetId(targetId);
        r.setTs(Instant.now());
        r.setTestType(spec.type());
        r.setTargetDesc(Strings.truncate(spec.describe(), 1000));
        r.setSuccess(outcome.success());
        r.setLatencyMs(outcome.latencyMs());
        r.setMessage(Strings.truncate(outcome.message(), 2000));
        r.setDetails(outcome.details() == null || outcome.details().isEmpty() ? null : json.write(outcome.details()));
        r.setTriggeredBy(user);
        return results.save(r);
    }

    private void apply(ConnectivityTarget t, TargetRequest r) {
        t.setName(r.name().trim());
        t.setTestType(r.testType());
        t.setHost(Strings.trimToNull(r.host()));
        t.setPort(r.port());
        t.setUrl(Strings.trimToNull(r.url()));
        t.setHttpMethod(Strings.isBlank(r.httpMethod()) ? "GET" : r.httpMethod().toUpperCase());
        t.setExpectedStatus(r.expectedStatus());
        t.setTimeoutMs(r.timeoutMs() == null ? 5000 : r.timeoutMs());
        t.setScheduleType(r.scheduleType() == null ? ScheduleType.NONE : r.scheduleType());
        t.setIntervalSeconds(r.intervalSeconds());
        t.setCron(normalizeCron(r.cron()));
        t.setEnabled(r.enabled() == null || r.enabled());
        t.setFailureThreshold(r.failureThreshold() == null ? 3 : r.failureThreshold());
        t.setTags(Strings.trimToNull(r.tags()));
        switch (t.getTestType()) {
            case DNS -> require(t.getHost(), "Host");
            case TCP -> {
                require(t.getHost(), "Host");
                if (t.getPort() == null) throw ApiException.badRequest("Port is required for TCP tests");
            }
            case TLS -> require(t.getHost(), "Host");
            case HTTP -> {
                require(t.getUrl(), "URL");
                if (!t.getUrl().startsWith("http://") && !t.getUrl().startsWith("https://")) {
                    throw ApiException.badRequest("URL must start with http:// or https://");
                }
            }
        }
        if (t.getScheduleType() == ScheduleType.INTERVAL && t.getIntervalSeconds() == null) {
            throw ApiException.badRequest("intervalSeconds is required for interval schedules");
        }
        if (t.getScheduleType() == ScheduleType.CRON && (t.getCron() == null || !CronExpression.isValidExpression(t.getCron()))) {
            throw ApiException.badRequest("Invalid cron expression: " + r.cron());
        }
    }

    /** Accepts standard 5-field cron ("*&#47;5 * * * *") as well as Spring's 6-field form. */
    static String normalizeCron(String cron) {
        if (Strings.isBlank(cron)) return null;
        String c = cron.trim().replaceAll("\\s+", " ");
        return c.split(" ").length == 5 ? "0 " + c : c;
    }

    static Instant nextRun(ConnectivityTarget t) {
        if (!t.isEnabled()) return null;
        return switch (t.getScheduleType()) {
            case NONE -> null;
            case INTERVAL -> (t.getLastRunAt() == null ? Instant.now() : t.getLastRunAt()).plusSeconds(t.getIntervalSeconds());
            case CRON -> {
                try {
                    LocalDateTime next = CronExpression.parse(t.getCron()).next(LocalDateTime.now());
                    yield next == null ? null : next.atZone(ZoneId.systemDefault()).toInstant();
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    private static void require(String value, String name) {
        if (Strings.isBlank(value)) throw ApiException.badRequest(name + " is required");
    }

    static String alertKey(Long id) {
        return "connectivity:" + id;
    }

    private static Spec spec(ConnectivityTarget t) {
        return new Spec(t.getTestType(), t.getHost(), t.getPort(), t.getUrl(), t.getHttpMethod(), t.getExpectedStatus(), t.getTimeoutMs());
    }

    private static String describe(ConnectivityTarget t) {
        return t.getTestType() + " " + spec(t).describe();
    }

    private static String scheduleText(ConnectivityTarget t) {
        return switch (t.getScheduleType()) {
            case NONE -> "manual";
            case INTERVAL -> "every " + t.getIntervalSeconds() + "s";
            case CRON -> "cron " + t.getCron();
        };
    }

    private ConnectivityTarget target(Long id) {
        return targets.findById(id).orElseThrow(() -> ApiException.notFound("Connectivity target " + id));
    }

    private TargetDto toDto(ConnectivityTarget t, List<ConnectivityResult> last24h) {
        Double uptime = last24h.isEmpty() ? null : 100.0 * last24h.stream().filter(ConnectivityResult::isSuccess).count() / last24h.size();
        Long avg = last24h.isEmpty() ? null : Math.round(last24h.stream().mapToLong(r -> r.getLatencyMs() == null ? 0 : r.getLatencyMs()).average().orElse(0));
        return new TargetDto(t.getId(), t.getName(), t.getTestType(), t.getHost(), t.getPort(), t.getUrl(), t.getHttpMethod(),
                t.getExpectedStatus(), t.getTimeoutMs(), t.getScheduleType(), t.getIntervalSeconds(), t.getCron(), t.isEnabled(),
                t.getFailureThreshold(), t.getConsecutiveFailures(), t.getLastStatus(), t.getLastRunAt(), t.getLastLatencyMs(),
                Strings.asList(t.getTags()), nextRun(t), uptime == null ? null : Math.round(uptime * 10) / 10.0, avg, spec(t).describe());
    }

    private ResultDto toDto(ConnectivityResult r) {
        return new ResultDto(r.getId(), r.getTargetId(), r.getTs(), r.getTestType(), r.getTargetDesc(), r.isSuccess(), r.getLatencyMs(),
                r.getMessage(), json.read(r.getDetails()), r.getTriggeredBy());
    }

    /** Published when a target is created, changed or deleted so the scheduler can re-register it. */
    public record TargetChangedEvent(Long id, boolean deleted) {
    }

    Map<String, Object> stats() {
        List<ConnectivityTarget> all = targets.findAll();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("targets", all.size());
        stats.put("scheduled", all.stream().filter(t -> t.isEnabled() && t.getScheduleType() != ScheduleType.NONE).count());
        stats.put("up", all.stream().filter(t -> "UP".equals(t.getLastStatus())).count());
        stats.put("down", all.stream().filter(t -> "DOWN".equals(t.getLastStatus())).count());
        return stats;
    }
}
