package com.platform.portal.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.platform.portal.alerts.AlertType.EscalationStep;
import com.platform.portal.audit.AuditService;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.CurrentUser;
import com.platform.portal.common.Json;
import com.platform.portal.common.Strings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/** Effective alert policies: stored overrides merged over the built-in defaults of each {@link AlertType}. */
@Service
public class AlertRuleService {

    private final AlertRule.Repository repository;
    private final Json json;
    private final AuditService audit;
    private final Map<AlertType, Policy> cache = new ConcurrentHashMap<>();

    public AlertRuleService(AlertRule.Repository repository, Json json, AuditService audit) {
        this.repository = repository;
        this.json = json;
        this.audit = audit;
    }

    /** Effective policy of a type; also used as the API representation. */
    public record Policy(AlertType type, String label, String description, String source, boolean enabled, Alert.Severity severity,
                         int minOccurrences, int pendingSeconds, int repeatMinutes, double backoffMultiplier, int maxRepeatMinutes,
                         int maxNotifications, boolean notifyOnResolve, int resolveGraceSeconds, int staleMinutes,
                         int reopenWindowMinutes, boolean emailEnabled, List<String> emailRecipients, boolean teamsEnabled,
                         List<EscalationStep> escalation, Map<String, Object> params, Instant mutedUntil, String muteReason,
                         boolean customized, String updatedBy, Instant updatedAt) {

        public boolean isMuted(Instant now) {
            return mutedUntil != null && mutedUntil.isAfter(now);
        }

        public long param(String key, long fallback) {
            Object v = params.get(key);
            if (v instanceof Number n) return n.longValue();
            try {
                return v == null ? fallback : Long.parseLong(v.toString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** Delay before the n-th repeat notification (n starts at 1): repeat × backoff^(n-1), capped. */
        public long repeatDelayMinutes(int notificationsSent) {
            double minutes = repeatMinutes * Math.pow(Math.max(1.0, backoffMultiplier), Math.max(0, notificationsSent - 1));
            return (long) Math.min(minutes, Math.max(repeatMinutes, maxRepeatMinutes));
        }
    }

    public record PolicyRequest(@NotNull Boolean enabled, @NotNull Alert.Severity severity,
                                @Min(1) @Max(1000) int minOccurrences, @Min(0) @Max(86_400) int pendingSeconds,
                                @Min(0) @Max(10_080) int repeatMinutes, @DecimalMin("1.0") @DecimalMax("10.0") double backoffMultiplier,
                                @Min(1) @Max(43_200) int maxRepeatMinutes, @Min(0) @Max(1000) int maxNotifications,
                                boolean notifyOnResolve, @Min(0) @Max(86_400) int resolveGraceSeconds,
                                @Min(0) @Max(43_200) int staleMinutes, @Min(0) @Max(10_080) int reopenWindowMinutes,
                                boolean emailEnabled, List<String> emailRecipients, boolean teamsEnabled,
                                List<@Valid EscalationStep> escalation, Map<String, Object> params,
                                Instant mutedUntil, String muteReason) {
    }

    public Policy policy(AlertType type) {
        if (type == null) {
            return defaults(AlertType.KAFKA_UNREACHABLE, false);
        }
        return cache.computeIfAbsent(type, t -> repository.findById(t).map(this::toPolicy).orElseGet(() -> defaults(t, false)));
    }

    public List<Policy> all() {
        return Arrays.stream(AlertType.values()).map(this::policy).toList();
    }

    @Transactional
    public Policy save(AlertType type, PolicyRequest r) {
        List<EscalationStep> steps = r.escalation() == null ? List.of()
                : r.escalation().stream().filter(s -> s.afterMinutes() > 0)
                .sorted((a, b) -> Integer.compare(a.afterMinutes(), b.afterMinutes())).toList();
        for (String email : r.emailRecipients() == null ? List.<String>of() : r.emailRecipients()) {
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw ApiException.badRequest("Invalid email address: " + email);
        }
        Policy before = policy(type);
        AlertRule rule = repository.findById(type).orElseGet(() -> {
            AlertRule n = new AlertRule();
            n.setType(type);
            return n;
        });
        rule.setEnabled(r.enabled());
        rule.setSeverity(r.severity());
        rule.setMinOccurrences(r.minOccurrences());
        rule.setPendingSeconds(r.pendingSeconds());
        rule.setRepeatMinutes(r.repeatMinutes());
        rule.setBackoffMultiplier(r.backoffMultiplier());
        rule.setMaxRepeatMinutes(Math.max(r.maxRepeatMinutes(), r.repeatMinutes()));
        rule.setMaxNotifications(r.maxNotifications());
        rule.setNotifyOnResolve(r.notifyOnResolve());
        rule.setResolveGraceSeconds(r.resolveGraceSeconds());
        rule.setStaleMinutes(r.staleMinutes());
        rule.setReopenWindowMinutes(r.reopenWindowMinutes());
        rule.setEmailEnabled(r.emailEnabled());
        rule.setEmailRecipients(r.emailRecipients() == null || r.emailRecipients().isEmpty() ? null : String.join(",", r.emailRecipients()));
        rule.setTeamsEnabled(r.teamsEnabled());
        rule.setEscalation(json.write(steps));
        Map<String, Object> params = new LinkedHashMap<>(type.defaultParams());
        if (r.params() != null) {
            r.params().forEach((k, v) -> {
                if (type.defaultParams().containsKey(k) && v != null && !v.toString().isBlank()) params.put(k, v);
            });
        }
        rule.setParams(json.write(params));
        rule.setMutedUntil(r.mutedUntil());
        rule.setMuteReason(Strings.trimToNull(r.muteReason()));
        rule.setUpdatedBy(CurrentUser.username());
        rule.setUpdatedAt(Instant.now());
        audit.track("ALERT_RULE_UPDATE", "alert-rule", type.name(), Map.of("before", summary(before), "after", summary(toPolicy(rule))),
                () -> repository.save(rule));
        cache.remove(type);
        return policy(type);
    }

    @Transactional
    public Policy reset(AlertType type) {
        audit.track("ALERT_RULE_RESET", "alert-rule", type.name(), Map.of(), () -> repository.deleteById(type));
        cache.remove(type);
        return policy(type);
    }

    private Policy toPolicy(AlertRule r) {
        AlertType t = r.getType();
        List<EscalationStep> steps = r.getEscalation() == null ? List.of()
                : json.mapper().readValue(r.getEscalation(), new TypeReference<List<EscalationStep>>() {
                });
        Map<String, Object> params = new LinkedHashMap<>(t.defaultParams());
        params.putAll(json.readMap(r.getParams()));
        return new Policy(t, t.label(), t.description(), t.source(), r.isEnabled(), r.getSeverity(), r.getMinOccurrences(),
                r.getPendingSeconds(), r.getRepeatMinutes(), r.getBackoffMultiplier(), r.getMaxRepeatMinutes(), r.getMaxNotifications(),
                r.isNotifyOnResolve(), r.getResolveGraceSeconds(), r.getStaleMinutes(), r.getReopenWindowMinutes(), r.isEmailEnabled(),
                Strings.asList(r.getEmailRecipients()), r.isTeamsEnabled(), steps, params, r.getMutedUntil(), r.getMuteReason(),
                true, r.getUpdatedBy(), r.getUpdatedAt());
    }

    private static Policy defaults(AlertType t, boolean customized) {
        AlertType.Defaults d = t.defaults();
        return new Policy(t, t.label(), t.description(), t.source(), true, t.defaultSeverity(), d.minOccurrences(),
                d.pendingSeconds(), d.repeatMinutes(), 2.0, Math.max(720, d.repeatMinutes() * 4), 0, true,
                d.resolveGraceSecs(), d.staleMinutes(), 30, true, List.of(), true, d.escalation(),
                new LinkedHashMap<>(t.defaultParams()), null, null, customized, null, null);
    }

    private static Map<String, Object> summary(Policy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", p.enabled());
        m.put("severity", p.severity());
        m.put("minOccurrences", p.minOccurrences());
        m.put("pendingSeconds", p.pendingSeconds());
        m.put("repeat", p.repeatMinutes() + "m x" + p.backoffMultiplier() + " max " + p.maxRepeatMinutes() + "m");
        m.put("resolveGraceSeconds", p.resolveGraceSeconds());
        m.put("staleMinutes", p.staleMinutes());
        m.put("channels", (p.emailEnabled() ? "email " : "") + (p.teamsEnabled() ? "teams" : ""));
        m.put("escalationSteps", new ArrayList<>(p.escalation()).size());
        m.put("params", p.params());
        m.put("mutedUntil", p.mutedUntil());
        return m;
    }
}
