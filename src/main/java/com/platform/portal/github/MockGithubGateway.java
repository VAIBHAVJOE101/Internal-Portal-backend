package com.platform.portal.github;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.platform.portal.common.ApiException;
import com.platform.portal.github.GithubModels.Member;
import com.platform.portal.github.GithubModels.Repo;
import com.platform.portal.github.GithubModels.Team;
import com.platform.portal.github.GithubModels.Workflow;
import com.platform.portal.github.GithubModels.WorkflowRun;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** In-memory GitHub org with repositories, workflow runs and teams for mock mode. */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "mock")
public class MockGithubGateway implements GithubGateway {

    private static final String ORG = "acme-platform";
    private static final List<String> USERS = List.of("aisha-k", "bcarter", "chenwei", "dalvarez", "emma-novak", "fhaddad",
            "gpatel", "hlindqvist", "ivan-petrov", "jmorgan", "kofi-mensah", "lucia-rossi");

    private final List<Repo> repos = new ArrayList<>();
    private final Map<String, List<WorkflowRun>> runs = new ConcurrentHashMap<>();
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<String, Map<String, Member>> members = new ConcurrentHashMap<>();
    private final Map<String, List<Member>> invites = new ConcurrentHashMap<>();
    private final Random random = new Random(42);

    public MockGithubGateway() {
        String[][] repoSeed = {
                {"orders", "Java"}, {"payments", "Kotlin"}, {"api-gateway", "Go"}, {"platform-infra", "HCL"},
                {"devops-portal-backend", "Java"}, {"devops-portal-frontend", "TypeScript"}, {"kafka-connectors", "Java"},
                {"helm-charts", "Smarty"}, {"reports", "Python"}};
        Instant now = Instant.now();
        for (int i = 0; i < repoSeed.length; i++) {
            String name = repoSeed[i][0];
            repos.add(new Repo(name, ORG + "/" + name, true, "main", now.minus(i * 3L + 1, ChronoUnit.HOURS).toString(),
                    "https://github.com/" + ORG + "/" + name, repoSeed[i][1], name.equals("reports")));
            List<WorkflowRun> list = new CopyOnWriteArrayList<>();
            String[] workflows = name.equals("platform-infra") ? new String[]{"terraform-plan", "terraform-apply"}
                    : new String[]{"ci", "build-and-push", "deploy"};
            for (int r = 0; r < 12; r++) {
                String wf = workflows[r % workflows.length];
                Instant created = now.minus((long) i * 40 + r * 95L + random.nextInt(30), ChronoUnit.MINUTES);
                boolean running = r == 0 && i < 2;
                String conclusion = running ? null : pickConclusion(name, r);
                long duration = 60 + random.nextInt(900);
                list.add(new WorkflowRun(900_000L + i * 100 + r, wf, titleFor(wf, r), name, r % 4 == 0 ? "feature/" + wf + "-" + r : "main",
                        r % 3 == 0 ? "pull_request" : "push", running ? "in_progress" : "completed", conclusion,
                        USERS.get((i + r) % USERS.size()), null, 400 - r, 1, created.toString(),
                        created.plusSeconds(running ? 30 : duration).toString(), running ? null : duration,
                        "https://github.com/" + ORG + "/" + name + "/actions/runs/" + (900_000L + i * 100 + r),
                        Long.toHexString(random.nextLong()).substring(0, 7)));
            }
            runs.put(name, list);
        }
        team("devops_team", "Devops_team", "Platform & DevOps engineers - portal admins", "aisha-k", "bcarter", "chenwei", "dalvarez");
        team("sre", "SRE", "Site reliability engineering", "chenwei", "emma-novak", "fhaddad");
        team("payments", "Payments", "Payments squad", "gpatel", "hlindqvist", "ivan-petrov", "jmorgan");
        team("commerce", "Commerce", "Orders & checkout", "kofi-mensah", "lucia-rossi", "jmorgan");
        team("data", "Data", "Data engineering", "hlindqvist", "fhaddad");
        members.get("devops_team").put("aisha-k", member("aisha-k", "maintainer"));
        invites.put("sre", new CopyOnWriteArrayList<>(List.of(new Member("new-hire-2026", null, "member", null, "pending"))));
    }

    private String pickConclusion(String repo, int r) {
        if (repo.equals("payments") && r == 1) return "failure";
        if (repo.equals("api-gateway") && r == 2) return "failure";
        if (r == 5) return "cancelled";
        if (r == 7) return "skipped";
        return "success";
    }

    private static String titleFor(String workflow, int r) {
        return switch (workflow) {
            case "ci" -> List.of("Bump spring-boot to 4.1.1", "Fix flaky integration test", "Add retry to Kafka producer",
                    "Refactor order validation").get(r % 4);
            case "deploy" -> "Deploy to " + (r % 2 == 0 ? "prod" : "uat");
            case "terraform-plan", "terraform-apply" -> "Private endpoints for Event Hubs";
            default -> "Release v1." + (40 - r);
        };
    }

    private void team(String slug, String name, String description, String... users) {
        teams.put(slug, new Team(slug.hashCode() & 0xffffff, slug, name, description, "closed", users.length, null,
                "https://github.com/orgs/" + ORG + "/teams/" + slug));
        Map<String, Member> m = new ConcurrentHashMap<>();
        for (String u : users) m.put(u, member(u, "member"));
        members.put(slug, m);
        invites.putIfAbsent(slug, new CopyOnWriteArrayList<>());
    }

    private static Member member(String login, String role) {
        return new Member(login, "https://avatars.githubusercontent.com/u/" + Math.abs(login.hashCode() % 100000) + "?v=4", role,
                "https://github.com/" + login, "active");
    }

    @Override
    public String org() {
        return ORG;
    }

    @Override
    public List<Repo> repos() {
        return repos;
    }

    @Override
    public List<Workflow> workflows(String repo) {
        return runs(repo).stream().map(WorkflowRun::name).distinct()
                .map(n -> new Workflow(Math.abs((repo + n).hashCode()), n, ".github/workflows/" + n + ".yml", "active",
                        "https://github.com/" + ORG + "/" + repo + "/actions"))
                .toList();
    }

    @Override
    public List<WorkflowRun> runs(String repo, String branch, String status, int limit) {
        return runs(repo).stream()
                .filter(r -> branch == null || branch.equals(r.branch()))
                .filter(r -> status == null || status.equals(r.status()) || status.equals(r.conclusion()))
                .sorted(Comparator.comparing(WorkflowRun::createdAt).reversed())
                .limit(limit).toList();
    }

    @Override
    public List<WorkflowRun> recentRuns(int repoCount, int limit) {
        return runs.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing(WorkflowRun::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized void rerun(String repo, long runId, boolean failedOnly) {
        List<WorkflowRun> list = runs(repo);
        WorkflowRun old = list.stream().filter(r -> r.id() == runId).findFirst().orElseThrow(() -> ApiException.notFound("Run " + runId));
        list.replaceAll(r -> r.id() == runId ? new WorkflowRun(r.id(), r.name(), r.title(), r.repo(), r.branch(), r.event(),
                "queued", null, r.actor(), r.actorAvatar(), r.runNumber(), r.attempt() + 1, Instant.now().toString(),
                Instant.now().toString(), null, r.htmlUrl(), r.headSha()) : r);
        // simulate completion shortly after
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            list.replaceAll(r -> r.id() == runId ? new WorkflowRun(r.id(), r.name(), r.title(), r.repo(), r.branch(), r.event(),
                    "completed", "success", r.actor(), r.actorAvatar(), r.runNumber(), r.attempt(), r.createdAt(),
                    Instant.now().toString(), 4L + old.runNumber() % 50, r.htmlUrl(), r.headSha()) : r);
        });
    }

    @Override
    public synchronized void cancel(String repo, long runId) {
        List<WorkflowRun> list = runs(repo);
        WorkflowRun run = list.stream().filter(r -> r.id() == runId).findFirst().orElseThrow(() -> ApiException.notFound("Run " + runId));
        if ("completed".equals(run.status())) {
            throw ApiException.conflict("Run " + runId + " already completed");
        }
        list.replaceAll(r -> r.id() == runId ? new WorkflowRun(r.id(), r.name(), r.title(), r.repo(), r.branch(), r.event(),
                "completed", "cancelled", r.actor(), r.actorAvatar(), r.runNumber(), r.attempt(), r.createdAt(),
                Instant.now().toString(), null, r.htmlUrl(), r.headSha()) : r);
    }

    @Override
    public List<Team> teams() {
        return teams.values().stream().map(t -> new Team(t.id(), t.slug(), t.name(), t.description(), t.privacy(),
                members.get(t.slug()).size(), t.parent(), t.htmlUrl())).toList();
    }

    @Override
    public List<Member> teamMembers(String slug) {
        return members(slug).values().stream().sorted(Comparator.comparing(Member::login)).toList();
    }

    @Override
    public List<Member> pendingInvitations(String slug) {
        members(slug);
        return invites.getOrDefault(slug, List.of());
    }

    @Override
    public String addMember(String slug, String username, String role) {
        Map<String, Member> m = members(slug);
        if (!USERS.contains(username)) {
            invites.get(slug).add(new Member(username, null, role, null, "pending"));
            return "pending";
        }
        m.put(username, member(username, role == null ? "member" : role));
        return "active";
    }

    @Override
    public void removeMember(String slug, String username) {
        Map<String, Member> m = members(slug);
        boolean removed = m.remove(username) != null;
        removed |= invites.get(slug).removeIf(i -> i.login().equals(username));
        if (!removed) {
            throw ApiException.notFound("Member " + username + " in team " + slug);
        }
    }

    @Override
    public List<Member> orgMembers() {
        return USERS.stream().map(u -> member(u, null)).toList();
    }

    private List<WorkflowRun> runs(String repo) {
        List<WorkflowRun> list = runs.get(repo);
        if (list == null) throw ApiException.notFound("Repository " + repo);
        return list;
    }

    private Map<String, Member> members(String slug) {
        Map<String, Member> m = members.get(slug);
        if (m == null) throw ApiException.notFound("Team " + slug);
        return m;
    }
}
