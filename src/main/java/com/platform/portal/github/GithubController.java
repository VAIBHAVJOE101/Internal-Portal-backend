package com.platform.portal.github;

import java.util.List;
import java.util.Map;

import com.platform.portal.audit.AuditService;
import com.platform.portal.github.GithubModels.AddMemberRequest;
import com.platform.portal.github.GithubModels.Member;
import com.platform.portal.github.GithubModels.Repo;
import com.platform.portal.github.GithubModels.Team;
import com.platform.portal.github.GithubModels.Workflow;
import com.platform.portal.github.GithubModels.WorkflowRun;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github")
public class GithubController {

    private final GithubGateway github;
    private final AuditService audit;

    public GithubController(GithubGateway github, AuditService audit) {
        this.github = github;
        this.audit = audit;
    }

    @GetMapping("/org")
    public Map<String, String> org() {
        return Map.of("org", github.org());
    }

    @GetMapping("/repos")
    public List<Repo> repos() {
        return github.repos();
    }

    @GetMapping("/repos/{repo}/workflows")
    public List<Workflow> workflows(@PathVariable String repo) {
        return github.workflows(repo);
    }

    @GetMapping("/runs")
    public List<WorkflowRun> runs(@RequestParam(required = false) String repo,
                                  @RequestParam(required = false) String branch,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "50") int limit) {
        if (repo == null || repo.isBlank()) {
            return github.recentRuns(10, Math.min(limit, 100));
        }
        return github.runs(repo, branch, status, Math.min(limit, 100));
    }

    @PostMapping("/repos/{repo}/runs/{runId}/rerun")
    public ResponseEntity<Void> rerun(@PathVariable String repo, @PathVariable long runId,
                                      @RequestParam(defaultValue = "false") boolean failedOnly) {
        audit.track("GITHUB_RUN_RERUN", "workflow-run", repo + "#" + runId, Map.of("failedOnly", failedOnly),
                () -> github.rerun(repo, runId, failedOnly));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/repos/{repo}/runs/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String repo, @PathVariable long runId) {
        audit.track("GITHUB_RUN_CANCEL", "workflow-run", repo + "#" + runId, Map.of(), () -> github.cancel(repo, runId));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/teams")
    public List<Team> teams() {
        return github.teams();
    }

    @GetMapping("/teams/{slug}/members")
    public List<Member> members(@PathVariable String slug) {
        return github.teamMembers(slug);
    }

    @GetMapping("/teams/{slug}/invitations")
    public List<Member> invitations(@PathVariable String slug) {
        return github.pendingInvitations(slug);
    }

    @PostMapping("/teams/{slug}/members")
    public Map<String, String> addMember(@PathVariable String slug, @Valid @RequestBody AddMemberRequest request) {
        String role = request.role() == null ? "member" : request.role();
        String state = audit.track("GITHUB_TEAM_ADD_MEMBER", "github-team", slug + "/" + request.username(), Map.of("role", role),
                () -> github.addMember(slug, request.username(), role));
        return Map.of("username", request.username(), "state", state);
    }

    @DeleteMapping("/teams/{slug}/members/{username}")
    public ResponseEntity<Void> removeMember(@PathVariable String slug, @PathVariable String username) {
        audit.track("GITHUB_TEAM_REMOVE_MEMBER", "github-team", slug + "/" + username, Map.of(),
                () -> github.removeMember(slug, username));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members")
    public List<Member> orgMembers() {
        return github.orgMembers();
    }
}
