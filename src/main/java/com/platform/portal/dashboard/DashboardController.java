package com.platform.portal.dashboard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.platform.portal.alerts.AlertService;
import com.platform.portal.audit.AuditService;
import com.platform.portal.boards.BoardsGateway;
import com.platform.portal.boards.BoardsModels.Iteration;
import com.platform.portal.boards.BoardsModels.WorkItem;
import com.platform.portal.common.Threads;
import com.platform.portal.connectivity.ConnectivityService;
import com.platform.portal.github.GithubGateway;
import com.platform.portal.inventory.InventoryDtos.PageDto;
import com.platform.portal.inventory.InventoryService;
import com.platform.portal.kafka.KafkaService;
import jakarta.annotation.PreDestroy;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One call that powers the overview page. Sections are loaded in parallel and independently, so an
 * unconfigured or unreachable integration only blanks its own card instead of failing the dashboard.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final KafkaService kafka;
    private final AlertService alerts;
    private final InventoryService inventory;
    private final ConnectivityService connectivity;
    private final BoardsGateway boards;
    private final GithubGateway github;
    private final AuditService audit;
    private final ExecutorService executor = Threads.pool("dashboard", 16);

    public DashboardController(KafkaService kafka, AlertService alerts, InventoryService inventory, ConnectivityService connectivity,
                               BoardsGateway boards, GithubGateway github, AuditService audit) {
        this.kafka = kafka;
        this.alerts = alerts;
        this.inventory = inventory;
        this.connectivity = connectivity;
        this.boards = boards;
        this.github = github;
        this.audit = audit;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        ExecutorService secured = new DelegatingSecurityContextExecutorService(executor);
        Map<String, CompletableFuture<Object>> parts = new LinkedHashMap<>();
        parts.put("kafka", async(secured, this::kafkaSection));
        parts.put("alerts", async(secured, () -> Map.of("summary", alerts.summary(), "trend", alerts.trend(14))));
        parts.put("expiring", async(secured, () -> {
            var items = inventory.expiring(30);
            return Map.of("count", items.size(), "expired", items.stream().filter(i -> i.daysLeft() < 0).count(),
                    "items", items.stream().limit(6).toList());
        }));
        parts.put("inventory", async(secured, () -> {
            List<PageDto> pages = inventory.listPages();
            return Map.of("pages", pages.size(), "records", pages.stream().mapToLong(PageDto::recordCount).sum(),
                    "byPage", pages.stream().map(p -> Map.of("slug", p.slug(), "name", p.name(), "count", p.recordCount())).toList());
        }));
        parts.put("connectivity", async(secured, () -> connectivity.list().stream()
                .map(t -> Map.of("id", t.id(), "name", t.name(), "status", t.lastStatus() == null ? "UNKNOWN" : t.lastStatus(),
                        "uptime", t.uptime24h() == null ? -1 : t.uptime24h()))
                .toList()));
        parts.put("sprint", async(secured, this::sprintSection));
        parts.put("runs", async(secured, () -> github.recentRuns(6, 8)));
        parts.put("audit", async(secured, () -> audit.recent(8)));

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        parts.forEach((key, future) -> {
            try {
                result.put(key, future.get(25, TimeUnit.SECONDS));
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                result.put(key, null);
                errors.put(key, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            }
        });
        result.put("errors", errors);
        return result;
    }

    private Object kafkaSection() {
        List<KafkaService.InstanceCard> cards = kafka.instances();
        Map<String, Long> byStatus = cards.stream().collect(Collectors.groupingBy(
                c -> c.lastHealth() == null ? "UNKNOWN" : c.lastHealth().getStatus(), LinkedHashMap::new, Collectors.counting()));
        int failedConnectors = cards.stream().mapToInt(c -> c.lastHealth() == null || c.lastHealth().getConnectorsFailed() == null ? 0
                : c.lastHealth().getConnectorsFailed()).sum();
        int connectors = cards.stream().mapToInt(c -> c.lastHealth() == null || c.lastHealth().getConnectorsTotal() == null ? 0
                : c.lastHealth().getConnectorsTotal()).sum();
        return Map.of("instances", cards, "byStatus", byStatus, "failedConnectors", failedConnectors, "connectors", connectors);
    }

    private Object sprintSection() {
        Iteration current = boards.currentIteration();
        List<WorkItem> items = boards.iterationItems(current.id());
        Map<String, Long> byState = items.stream().collect(Collectors.groupingBy(WorkItem::state, LinkedHashMap::new, Collectors.counting()));
        long done = items.stream().filter(i -> "Closed".equals(i.state()) || "Done".equals(i.state()) || "Resolved".equals(i.state())).count();
        Map<String, Object> sprint = new LinkedHashMap<>();
        sprint.put("iteration", current);
        sprint.put("total", items.size());
        sprint.put("done", done);
        sprint.put("byState", byState);
        return sprint;
    }

    private static CompletableFuture<Object> async(ExecutorService executor, Supplier<Object> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
