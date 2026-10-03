package com.platform.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.platform.portal.alerts.Alert;
import com.platform.portal.alerts.AlertEvent;
import com.platform.portal.alerts.AlertRepository;
import com.platform.portal.alerts.AlertRuleService;
import com.platform.portal.alerts.AlertRuleService.PolicyRequest;
import com.platform.portal.alerts.AlertService;
import com.platform.portal.alerts.AlertType;
import com.platform.portal.alerts.AlertType.EscalationStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/** Alert engine behaviour: pending → firing, escalation, grace-period auto-resolve, reopen, disabled rules. */
@SpringBootTest
@ActiveProfiles("mock")
class AlertLifecycleTests {

    private static final AlertType TYPE = AlertType.KAFKA_UNDER_REPLICATED;

    @Autowired AlertService alerts;
    @Autowired AlertRuleService rules;
    @Autowired AlertRepository repository;
    @Autowired AlertEvent.Repository events;

    @BeforeEach
    void admin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    @AfterEach
    void reset() {
        rules.reset(TYPE);
        SecurityContextHolder.clearContext();
    }

    private void policy(boolean enabled, int minOccurrences, int graceSeconds, List<EscalationStep> escalation) {
        rules.save(TYPE, new PolicyRequest(enabled, Alert.Severity.WARNING, minOccurrences, 0, 15, 2.0, 120, 0, true,
                graceSeconds, 0, 30, false, List.of(), false, escalation, Map.of(), null, null));
    }

    @Test
    void firesOnlyAfterMinimumOccurrencesAndAutoResolves() {
        policy(true, 2, 0, List.of());
        String key = "test:min-occurrences";
        Alert first = alerts.raise(TYPE, key, null, "URP", null, "cluster");
        assertThat(first.getStatus()).isEqualTo(Alert.Status.PENDING);

        Alert second = alerts.raise(TYPE, key, null, "URP", null, "cluster");
        assertThat(second.getStatus()).isEqualTo(Alert.Status.OPEN);
        assertThat(second.getFiredAt()).isNotNull();
        assertThat(second.getNextNotifyAt()).isAfter(Instant.now().plus(14, ChronoUnit.MINUTES));

        alerts.resolve(key);
        Alert resolved = repository.findById(second.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(Alert.Status.RESOLVED);
        assertThat(resolved.getResolvedReason()).contains("Auto-resolved");

        // condition returns within the reopen window -> same alert is reopened instead of duplicated
        Alert reopened = alerts.raise(TYPE, key, null, "URP", null, "cluster");
        assertThat(reopened.getId()).isEqualTo(second.getId());
        assertThat(reopened.getReopenCount()).isEqualTo(1);
        assertThat(events.findByAlertIdOrderByTsAscIdAsc(reopened.getId())).extracting(AlertEvent::getKind)
                .contains(AlertEvent.Kind.RAISED, AlertEvent.Kind.FIRED, AlertEvent.Kind.RESOLVED, AlertEvent.Kind.REOPENED);
    }

    @Test
    void pendingAlertThatClearsIsDroppedSilently() {
        policy(true, 3, 0, List.of());
        Alert a = alerts.raise(TYPE, "test:blip", null, "blip", null, null);
        alerts.resolve("test:blip");
        Alert after = repository.findById(a.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Alert.Status.RESOLVED);
        assertThat(after.getFiredAt()).isNull();
        assertThat(after.getNotificationCount()).isZero();
    }

    @Test
    void resolveWaitsForGracePeriod() {
        policy(true, 1, 60, List.of());
        Alert a = alerts.raise(TYPE, "test:grace", null, "grace", null, null);
        alerts.resolve("test:grace");
        Alert clearing = repository.findById(a.getId()).orElseThrow();
        assertThat(clearing.getStatus()).isEqualTo(Alert.Status.OPEN);
        assertThat(clearing.getClearedSince()).isNotNull();

        clearing.setClearedSince(Instant.now().minusSeconds(61));
        repository.save(clearing);
        alerts.evaluate();
        assertThat(repository.findById(a.getId()).orElseThrow().getStatus()).isEqualTo(Alert.Status.RESOLVED);
    }

    @Test
    void escalatesUnacknowledgedAlertAndRaisesSeverity() {
        policy(true, 1, 0, List.of(new EscalationStep(10, List.of("oncall@acme.io"), false, true)));
        Alert a = alerts.raise(TYPE, "test:escalate", null, "escalate", null, null);
        assertThat(a.getSeverity()).isEqualTo(Alert.Severity.WARNING);

        a.setFiredAt(Instant.now().minus(11, ChronoUnit.MINUTES));
        repository.save(a);
        alerts.evaluate();
        Alert escalated = repository.findById(a.getId()).orElseThrow();
        assertThat(escalated.getEscalationLevel()).isEqualTo(1);
        assertThat(escalated.getSeverity()).isEqualTo(Alert.Severity.CRITICAL);
    }

    @Test
    void disabledRuleSuppressesAndClosesAlerts() {
        policy(true, 1, 0, List.of());
        Alert a = alerts.raise(TYPE, "test:disabled", null, "x", null, null);
        policy(false, 1, 0, List.of());
        assertThat(alerts.raise(TYPE, "test:disabled-2", null, "y", null, null)).isNull();
        alerts.evaluate();
        assertThat(repository.findById(a.getId()).orElseThrow().getResolvedReason()).isEqualTo("Rule disabled");
    }
}
