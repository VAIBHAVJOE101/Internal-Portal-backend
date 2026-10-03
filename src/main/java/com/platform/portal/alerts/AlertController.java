package com.platform.portal.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.platform.portal.alerts.AlertNotifier.Delivery;
import com.platform.portal.alerts.AlertRuleService.Policy;
import com.platform.portal.alerts.AlertRuleService.PolicyRequest;
import com.platform.portal.audit.AuditService;
import com.platform.portal.common.PageResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AlertRuleService rules;
    private final AlertStream alertStream;
    private final AlertNotifier notifier;
    private final AuditService audit;

    public AlertController(AlertService alertService, AlertRuleService rules, AlertStream alertStream, AlertNotifier notifier,
                           AuditService audit) {
        this.alertService = alertService;
        this.rules = rules;
        this.alertStream = alertStream;
        this.notifier = notifier;
        this.audit = audit;
    }

    @GetMapping
    public PageResult<AlertService.AlertDto> search(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String severity,
                                                    @RequestParam(required = false) String source,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return alertService.search(status, severity, source, type, q, page, size);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return alertService.summary();
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "14") int days) {
        return alertService.trend(Math.min(Math.max(days, 1), 90));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return alertStream.subscribe();
    }

    @GetMapping("/{id:\\d+}")
    public AlertService.AlertDto get(@PathVariable Long id) {
        return alertService.get(id);
    }

    @GetMapping("/{id:\\d+}/events")
    public List<AlertEvent> events(@PathVariable Long id) {
        return alertService.events(id);
    }

    @PostMapping("/{id:\\d+}/ack")
    public AlertService.AlertDto acknowledge(@PathVariable Long id) {
        return alertService.acknowledge(id);
    }

    @PostMapping("/{id:\\d+}/snooze")
    public AlertService.AlertDto snooze(@PathVariable Long id, @RequestParam int minutes) {
        return alertService.snooze(id, minutes);
    }

    @DeleteMapping("/{id:\\d+}/snooze")
    public AlertService.AlertDto unsnooze(@PathVariable Long id) {
        return alertService.unsnooze(id);
    }

    @PostMapping("/{id:\\d+}/resolve")
    public AlertService.AlertDto resolve(@PathVariable Long id) {
        return alertService.resolveById(id);
    }

    // ---------------------------------------------------------------- rules

    @GetMapping("/rules")
    public List<Policy> rules() {
        return rules.all();
    }

    @PutMapping("/rules/{type}")
    public Policy saveRule(@PathVariable AlertType type, @Valid @RequestBody PolicyRequest request) {
        return rules.save(type, request);
    }

    @DeleteMapping("/rules/{type}")
    public Policy resetRule(@PathVariable AlertType type) {
        return rules.reset(type);
    }

    /** Sends a sample notification through the channels configured for the rule. */
    @PostMapping("/rules/{type}/test")
    public List<Delivery> testRule(@PathVariable AlertType type) {
        Policy policy = rules.policy(type);
        AlertNotifier.Message m = new AlertNotifier.Message(null, type, policy.severity(), Alert.Status.OPEN,
                "Test: " + type.label(), type.description(), "Sample resource", Instant.now(), 1, 1, 0, null);
        List<Delivery> result = notifier.deliver(m, policy, AlertNotifier.Kind.TEST, List.of(), policy.teamsEnabled(), false);
        audit.record("ALERT_RULE_TEST", "alert-rule", type.name(),
                Map.of("deliveries", result.stream().map(d -> d.channel() + ": " + d.detail()).toList()), true, null);
        return result;
    }
}
