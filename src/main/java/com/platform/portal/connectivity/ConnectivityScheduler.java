package com.platform.portal.connectivity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import com.platform.portal.connectivity.ConnectivityTarget.ScheduleType;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Registers one scheduled job per enabled connectivity target (fixed interval or cron) and re-registers
 * it whenever the target changes. Each execution takes a ShedLock so only one replica runs a given tick.
 */
@Component
public class ConnectivityScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConnectivityScheduler.class);

    private final ConnectivityService service;
    private final TaskScheduler scheduler;
    private final LockingTaskExecutor locks;
    private final Map<Long, ScheduledFuture<?>> jobs = new ConcurrentHashMap<>();

    public ConnectivityScheduler(ConnectivityService service, TaskScheduler scheduler, LockingTaskExecutor locks) {
        this.service = service;
        this.scheduler = scheduler;
        this.locks = locks;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        service.scheduledTargets().forEach(this::register);
        scheduler.scheduleWithFixedDelay(this::prune, Instant.now().plusSeconds(60), Duration.ofHours(6));
        log.info("Scheduled {} connectivity test(s)", jobs.size());
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onChange(ConnectivityService.TargetChangedEvent event) {
        cancel(event.id());
        if (!event.deleted()) {
            ConnectivityTarget t = service.find(event.id());
            if (t != null && t.isEnabled() && t.getScheduleType() != ScheduleType.NONE) {
                register(t);
            }
        }
    }

    private void register(ConnectivityTarget t) {
        Long id = t.getId();
        Duration lockAtMost;
        ScheduledFuture<?> future;
        try {
            if (t.getScheduleType() == ScheduleType.INTERVAL) {
                Duration interval = Duration.ofSeconds(Math.max(30, t.getIntervalSeconds()));
                lockAtMost = interval.multipliedBy(2);
                Duration lockAtLeast = interval.dividedBy(2);
                future = scheduler.scheduleAtFixedRate(() -> run(id, lockAtMost, lockAtLeast), Instant.now().plusSeconds(10), interval);
            } else {
                lockAtMost = Duration.ofMinutes(5);
                future = scheduler.schedule(() -> run(id, lockAtMost, Duration.ofSeconds(5)), new CronTrigger(t.getCron()));
            }
            if (future != null) {
                jobs.put(id, future);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Could not schedule connectivity target {} ({}): {}", id, t.getName(), e.getMessage());
        }
    }

    private void run(Long id, Duration lockAtMost, Duration lockAtLeast) {
        try {
            locks.executeWithLock((Runnable) () -> service.execute(id, "scheduler"),
                    new LockConfiguration(Instant.now(), "conn-" + id, lockAtMost, lockAtLeast));
        } catch (RuntimeException e) {
            log.warn("Scheduled connectivity test {} failed: {}", id, e.getMessage());
        }
    }

    private void cancel(Long id) {
        ScheduledFuture<?> f = jobs.remove(id);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void prune() {
        locks.executeWithLock((Runnable) service::pruneResults,
                new LockConfiguration(Instant.now(), "conn-prune", Duration.ofMinutes(10), Duration.ofMinutes(1)));
    }
}
