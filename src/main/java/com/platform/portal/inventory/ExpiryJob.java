package com.platform.portal.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import com.platform.portal.alerts.Alert;
import com.platform.portal.alerts.AlertRuleService;
import com.platform.portal.alerts.AlertService;
import com.platform.portal.alerts.AlertType;
import com.platform.portal.config.PortalProperties;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Raises alerts for expiry-tracked inventory dates (secrets, certificates, licences...):
 * Thresholds come from the CREDENTIAL_EXPIRY alert rule (default: WARNING within 30 days, CRITICAL within 7). Alerts auto-resolve when the date is renewed.
 */
@Component
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);
    private static final String PREFIX = "expiry:";

    private final InventoryService inventory;
    private final AlertService alerts;
    private final AlertRuleService rules;
    private final LockingTaskExecutor locks;
    private final TaskScheduler scheduler;
    private final PortalProperties properties;

    public ExpiryJob(InventoryService inventory, AlertService alerts, AlertRuleService rules, LockingTaskExecutor locks, TaskScheduler scheduler,
                     PortalProperties properties) {
        this.inventory = inventory;
        this.alerts = alerts;
        this.rules = rules;
        this.locks = locks;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void schedule() {
        Duration interval = properties.jobs().expiryCheckInterval();
        scheduler.scheduleWithFixedDelay(this::runLocked, Instant.now().plusSeconds(15), interval);
    }

    void runLocked() {
        locks.executeWithLock((Runnable) this::check,
                new LockConfiguration(Instant.now(), "inventory-expiry", Duration.ofMinutes(10), Duration.ofSeconds(30)));
    }

    public void check() {
        try {
            Set<String> active = new HashSet<>();
            AlertRuleService.Policy policy = rules.policy(AlertType.CREDENTIAL_EXPIRY);
            long warnDays = policy.param("warnDays", 30);
            long criticalDays = policy.param("criticalDays", 7);
            for (InventoryDtos.ExpiringItem item : inventory.expiring((int) warnDays)) {
                String key = PREFIX + item.pageSlug() + ":" + item.recordId() + ":" + item.columnKey();
                active.add(key);
                Alert.Severity severity = item.daysLeft() <= criticalDays ? Alert.Severity.CRITICAL : Alert.Severity.WARNING;
                String when = item.daysLeft() < 0 ? "expired " + (-item.daysLeft()) + " day(s) ago"
                        : item.daysLeft() == 0 ? "expires today" : "expires in " + item.daysLeft() + " day(s)";
                alerts.raise(AlertType.CREDENTIAL_EXPIRY, key, severity, item.title() + " " + when,
                        item.columnLabel() + " on " + item.pageName() + ": " + item.expiresOn(), item.pageName() + " / " + item.title());
            }
            alerts.resolveMissing(PREFIX, active);
        } catch (RuntimeException e) {
            log.warn("Expiry check failed", e);
        }
    }
}
