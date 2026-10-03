package com.platform.portal.alerts;

import java.time.Duration;
import java.time.Instant;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/** Runs the alert lifecycle evaluation every 30 seconds on exactly one replica. */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    private static final Duration TICK = Duration.ofSeconds(30);

    private final AlertService alerts;
    private final TaskScheduler scheduler;
    private final LockingTaskExecutor locks;

    public AlertEngine(AlertService alerts, TaskScheduler scheduler, LockingTaskExecutor locks) {
        this.alerts = alerts;
        this.scheduler = scheduler;
        this.locks = locks;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, Instant.now().plusSeconds(10), TICK);
    }

    void tick() {
        try {
            locks.executeWithLock((Runnable) alerts::evaluate,
                    new LockConfiguration(Instant.now(), "alert-engine", Duration.ofMinutes(2), Duration.ofSeconds(10)));
        } catch (RuntimeException e) {
            log.warn("Alert engine tick failed", e);
        }
    }
}
