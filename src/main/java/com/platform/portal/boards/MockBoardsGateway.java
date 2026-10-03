package com.platform.portal.boards;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.platform.portal.boards.BoardsModels.Comment;
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
    private final Map<Integer, List<Comment>> comments = new ConcurrentHashMap<>();
    private final AtomicInteger commentIds = new AtomicInteger(9000);

    private static final String ACCEPTANCE = "<ul><li>Change is applied in dev, uat and prod</li><li>Runbook updated</li>"
            + "<li>Dashboards and alerts show no regression for 24h</li></ul>";

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
                    "https://dev.azure.com/acme/Platform/_workitems/edit/" + id, Instant.now().toString(), 1,
                    descriptionFor((String) s[0], type), "User Story".equals(type) ? ACCEPTANCE : null, 0));
            seedComments(id, who);
            id++;
        }
    }

    private static String descriptionFor(String title, String type) {
        if ("Bug".equals(type)) {
            return "<p><b>Observed:</b> " + title + ".</p><p><b>Repro steps</b></p><ol><li>Open the Kafka page in the portal</li>"
                    + "<li>Check the affected cluster / consumer group</li><li>Compare with the Grafana dashboard</li></ol>"
                    + "<p><b>Expected:</b> no failed tasks and lag below the alert threshold.</p>";
        }
        return "<p>" + title + ".</p><p>Context: part of the platform reliability roadmap for this quarter. "
                + "Coordinate the change window in <a href=\"https://teams.microsoft.com\">#platform-ops</a> and record it in the "
                + "change calendar.</p><ul><li>Prepare and review the plan</li><li>Execute in uat, then prod</li><li>Validate monitoring</li></ul>";
    }

    private void seedComments(int id, Person assignee) {
        List<Comment> list = new CopyOnWriteArrayList<>();
        if (id % 3 == 0) {
            list.add(new Comment(commentIds.incrementAndGet(), "<div>Kicked this off – plan is in the linked wiki page.</div>",
                    assignee == null ? TEAM.get(0) : assignee, Instant.now().minusSeconds(86_400 * 2).toString(), null));
            list.add(new Comment(commentIds.incrementAndGet(), "<div>Looks good. Please schedule the prod step outside business hours.</div>",
                    TEAM.get(4), Instant.now().minusSeconds(3600 * 5).toString(), null));
        } else if (id % 3 == 1) {
            list.add(new Comment(commentIds.incrementAndGet(), "<div>Blocked on access to the target subscription, waiting for approval.</div>",
                    assignee == null ? TEAM.get(1) : assignee, Instant.now().minusSeconds(3600 * 20).toString(), null));
        }
        comments.put(id, list);
    }

    private WorkItem withCount(WorkItem w) {
        return new WorkItem(w.id(), w.title(), w.type(), w.state(), w.assignedTo(), w.startDate(), w.endDate(), w.remainingWork(),
                w.storyPoints(), w.priority(), w.tags(), w.parentId(), w.url(), w.changedDate(), w.rev(), w.description(),
                w.acceptanceCriteria(), comments.getOrDefault(w.id(), List.of()).size());
    }

    @Override
    public List<Comment> comments(int id) {
        get(id);
        return List.copyOf(comments.getOrDefault(id, List.of()));
    }

    @Override
    public Comment addComment(int id, String html) {
        get(id);
        Person author = TEAM.get(0);
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) author = new Person("admin".equals(auth.getName()) ? "Demo Admin" : auth.getName(), auth.getName() + "@acme.io", null);
        Comment c = new Comment(commentIds.incrementAndGet(), html, author, Instant.now().toString(), null);
        comments.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(c);
        return c;
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
        return items.values().stream().sorted((a, b) -> Integer.compare(a.id(), b.id())).map(this::withCount).toList();
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
        return withCount(item);
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
                w.parentId(), w.url(), Instant.now().toString(), w.rev() + 1,
                u.description() == null ? w.description() : (u.description().isEmpty() ? null : u.description()),
                w.acceptanceCriteria(), w.commentCount());
        items.put(id, updated);
        return updated;
    }
}
