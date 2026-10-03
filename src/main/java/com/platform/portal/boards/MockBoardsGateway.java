package com.platform.portal.boards;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.platform.portal.boards.BoardsModels.Iteration;
import com.platform.portal.boards.BoardsModels.Person;
import com.platform.portal.boards.BoardsModels.WorkItem;
import com.platform.portal.boards.BoardsModels.WorkItemUpdate;
import com.platform.portal.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** In-memory sprint with realistic platform-team work items for mock mode. */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "mock")
public class MockBoardsGateway implements BoardsGateway {

    private static final List<Person> TEAM = List.of(
            new Person("Aisha Khan", "aisha.khan@acme.io", null),
            new Person("Ben Carter", "ben.carter@acme.io", null),
            new Person("Chen Wei", "chen.wei@acme.io", null),
            new Person("Diego Alvarez", "diego.alvarez@acme.io", null),
            new Person("Emma Novak", "emma.novak@acme.io", null),
            new Person("Farah Haddad", "farah.haddad@acme.io", null));

    private final List<Iteration> iterations;
    private final Map<Integer, WorkItem> items = new ConcurrentHashMap<>();

    public MockBoardsGateway() {
        LocalDate start = LocalDate.now().minusDays(6);
        iterations = List.of(
                iteration("sprint-41", "Sprint 41", start.minusDays(28), "past"),
                iteration("sprint-42", "Sprint 42", start.minusDays(14), "past"),
                iteration("sprint-43", "Sprint 43", start, "current"),
                iteration("sprint-44", "Sprint 44", start.plusDays(14), "future"));
        Object[][] seed = {
                {"Upgrade AKS prod cluster to 1.33", "User Story", "Active", 0, 1, 8.0},
                {"Drain and cordon node pool np-system-01", "Task", "Closed", 0, 1, null},
                {"Rotate Cosmos DB read keys for Kafka Portal", "Task", "New", 2, 1, null},
                {"Kafka Connect: fix audit-s3-sink IAM policy", "Bug", "Active", 3, 1, 3.0},
                {"Add DLQ alerting for elastic sinks", "User Story", "New", 3, 2, 5.0},
                {"Migrate Jenkins legacy jobs to GitHub Actions", "User Story", "Active", 1, 2, 13.0},
                {"Create reusable workflow for container builds", "Task", "Resolved", 1, 2, null},
                {"Renew api.acme.io TLS certificate", "Task", "Active", 4, 1, null},
                {"Document connectivity test runbook", "Task", "New", null, 3, null},
                {"Terraform: private endpoints for Event Hubs", "User Story", "Resolved", 5, 2, 8.0},
                {"Grafana dashboard for consumer lag", "Task", "Closed", 2, 2, null},
                {"Investigate fraud-detector consumer lag", "Bug", "New", 3, 1, 2.0},
                {"Enable network policies in payments namespace", "Task", "Active", 5, 2, null},
                {"Patch RHEL 9 Kafka brokers (CVE-2026-1102)", "Task", "New", 0, 1, null},
        };
        int id = 4810;
        for (Object[] s : seed) {
            Person who = s[3] == null ? null : TEAM.get((Integer) s[3]);
            String type = (String) s[1];
            String state = (String) s[2];
            LocalDate taskStart = "New".equals(state) ? null : start.plusDays(id % 5);
            LocalDate taskEnd = "Closed".equals(state) || "Resolved".equals(state) ? start.plusDays(id % 5 + 2) : null;
            items.put(id, new WorkItem(id, (String) s[0], type, state, who,
                    taskStart == null ? null : taskStart.atTime(9, 0).toInstant(ZoneOffset.UTC).toString(),
                    taskEnd == null ? null : taskEnd.atTime(17, 0).toInstant(ZoneOffset.UTC).toString(),
                    "Task".equals(type) ? (double) (id % 6 + 1) : null, (Double) s[5], (Integer) s[4],
                    "Bug".equals(type) ? List.of("ops", "incident") : List.of("platform"), null,
                    "https://dev.azure.com/acme/Platform/_workitems/edit/" + id, Instant.now().toString(), 1));
            id++;
        }
    }

    private static Iteration iteration(String id, String name, LocalDate start, String timeFrame) {
        return new Iteration(id, name, "Platform\\" + name, start.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
                start.plusDays(13).atStartOfDay().toInstant(ZoneOffset.UTC).toString(), timeFrame);
    }

    @Override
    public List<Iteration> iterations() {
        return iterations;
    }

    @Override
    public Iteration currentIteration() {
        return iterations.get(2);
    }

    @Override
    public List<WorkItem> iterationItems(String iterationId) {
        if (!"sprint-43".equals(iterationId)) {
            return List.of();
        }
        return items.values().stream().sorted((a, b) -> Integer.compare(a.id(), b.id())).toList();
    }

    @Override
    public List<String> columns(List<WorkItem> workItems) {
        LinkedHashSet<String> cols = new LinkedHashSet<>(List.of("New", "Active", "Resolved", "Closed"));
        workItems.forEach(w -> cols.add(w.state()));
        return new ArrayList<>(cols);
    }

    @Override
    public List<Person> teamMembers() {
        return TEAM;
    }

    @Override
    public WorkItem get(int id) {
        WorkItem item = items.get(id);
        if (item == null) throw ApiException.notFound("Work item " + id);
        return item;
    }

    @Override
    public synchronized WorkItem update(int id, WorkItemUpdate u) {
        WorkItem w = get(id);
        if (u.rev() != null && u.rev() != w.rev()) {
            throw ApiException.conflict("Work item " + id + " was changed by someone else (rev " + w.rev() + "). Refresh and retry.");
        }
        Person assignee = w.assignedTo();
        if (u.assignedTo() != null) {
            assignee = u.assignedTo().isEmpty() ? null : TEAM.stream().filter(p -> p.uniqueName().equalsIgnoreCase(u.assignedTo()))
                    .findFirst().orElseThrow(() -> ApiException.badRequest("Unknown team member " + u.assignedTo()));
        }
        WorkItem updated = new WorkItem(w.id(), u.title() == null ? w.title() : u.title(), w.type(),
                u.state() == null ? w.state() : u.state(), assignee,
                u.startDate() == null ? w.startDate() : (u.startDate().isEmpty() ? null : u.startDate()),
                u.endDate() == null ? w.endDate() : (u.endDate().isEmpty() ? null : u.endDate()),
                u.remainingWork() == null ? w.remainingWork() : u.remainingWork(), w.storyPoints(), w.priority(), w.tags(),
                w.parentId(), w.url(), Instant.now().toString(), w.rev() + 1);
        items.put(id, updated);
        return updated;
    }
}
