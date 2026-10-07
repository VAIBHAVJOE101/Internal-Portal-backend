package com.platform.portal.github;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.platform.portal.common.ApiException;
import com.platform.portal.common.Strings;
import com.platform.portal.common.Threads;
import com.platform.portal.config.PortalProperties;
import com.platform.portal.github.GithubModels.Member;
import com.platform.portal.github.GithubModels.Repo;
import com.platform.portal.github.GithubModels.Team;
import com.platform.portal.github.GithubModels.Workflow;
import com.platform.portal.github.GithubModels.WorkflowRun;
import com.platform.portal.settings.IntegrationProbe;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

/**
 * GitHub REST v3 client. Calls run with the signed-in user's OAuth token (so GitHub enforces the
 * user's own permissions on team changes); background calls fall back to the service token in Settings.
 */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "real", matchIfMissing = true)
public class RealGithubGateway implements GithubGateway, IntegrationProbe {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {
    };

    private final SettingsService settings;
    private final PortalProperties properties;
    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClients;
    private final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    private final ExecutorService executor = Threads.pool("github", 16);

    public RealGithubGateway(SettingsService settings, PortalProperties properties,
                             ObjectProvider<OAuth2AuthorizedClientService> authorizedClients) {
        this.settings = settings;
        this.properties = properties;
        this.authorizedClients = authorizedClients;
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
    }

    // ------------------------------------------------------------------ setup

    private record Ctx(RestClient client, String apiUrl, String org) {
        URI uri(String path) {
            return URI.create(apiUrl + path);
        }
    }

    private Ctx ctx() {
        return ctx(settings.resolve(SettingType.GITHUB), userToken());
    }

    private Ctx ctx(Map<String, String> cfg, String token) {
        String org = !Strings.isBlank(cfg.get("org")) ? cfg.get("org") : properties.auth().githubOrg();
        if (Strings.isBlank(org)) {
            throw ApiException.notConfigured("GitHub (org)");
        }
        String effective = token != null ? token : cfg.get("token");
        if (Strings.isBlank(effective)) {
            throw ApiException.notConfigured("GitHub (no user OAuth token and no service token)");
        }
        String apiUrl = cfg.getOrDefault("apiUrl", "https://api.github.com").replaceAll("/+$", "");
        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + effective)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        return new Ctx(client, apiUrl, org);
    }

    /** OAuth token of the current user, if they logged in with GitHub. */
    private String userToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        OAuth2AuthorizedClientService service = authorizedClients.getIfAvailable();
        if (service == null || !(auth instanceof OAuth2AuthenticationToken token)) {
            return null;
        }
        OAuth2AuthorizedClient client = service.loadAuthorizedClient(token.getAuthorizedClientRegistrationId(), token.getName());
        return client == null ? null : client.getAccessToken().getTokenValue();
    }

    @Override
    public SettingType type() {
        return SettingType.GITHUB;
    }

    @Override
    public Map<String, Object> probe(Map<String, String> cfg) {
        Ctx ctx = ctx(cfg, null);
        Map<String, Object> org = get(ctx, "/orgs/" + seg(ctx.org()), MAP);
        return Map.of("org", String.valueOf(org.get("login")), "publicRepos", String.valueOf(org.get("public_repos")));
    }

    @Override
    public String org() {
        Map<String, String> cfg = settings.resolve(SettingType.GITHUB);
        return !Strings.isBlank(cfg.get("org")) ? cfg.get("org") : properties.auth().githubOrg();
    }

    // ------------------------------------------------------------------ repos & actions

    @Override
    public List<Repo> repos() {
        Ctx ctx = ctx();
        return paged(ctx, "/orgs/" + seg(ctx.org()) + "/repos?sort=pushed&type=all", 5).stream()
                .map(r -> new Repo((String) r.get("name"), (String) r.get("full_name"), Boolean.TRUE.equals(r.get("private")),
                        (String) r.get("default_branch"), (String) r.get("pushed_at"), (String) r.get("html_url"),
                        (String) r.get("language"), Boolean.TRUE.equals(r.get("archived"))))
                .toList();
    }

    @Override
    public List<Workflow> workflows(String repo) {
        Ctx ctx = ctx();
        Map<String, Object> body = get(ctx, repoPath(ctx, repo) + "/actions/workflows?per_page=100", MAP);
        return list(body.get("workflows")).stream()
                .map(w -> new Workflow(((Number) w.get("id")).longValue(), (String) w.get("name"), (String) w.get("path"),
                        (String) w.get("state"), (String) w.get("html_url")))
                .toList();
    }

    @Override
    public List<WorkflowRun> runs(String repo, String branch, String status, int limit) {
        Ctx ctx = ctx();
        return runs(ctx, repo, branch, status, limit);
    }

    private List<WorkflowRun> runs(Ctx ctx, String repo, String branch, String status, int limit) {
        StringBuilder path = new StringBuilder(repoPath(ctx, repo) + "/actions/runs?per_page=" + Math.min(Math.max(limit, 1), 100));
        if (!Strings.isBlank(branch)) path.append("&branch=").append(q(branch));
        if (!Strings.isBlank(status)) path.append("&status=").append(q(status));
        Map<String, Object> body = get(ctx, path.toString(), MAP);
        return list(body.get("workflow_runs")).stream().map(r -> toRun(r, repo)).toList();
    }

    @Override
    public List<WorkflowRun> recentRuns(int repoCount, int limit) {
        Ctx ctx = ctx();
        List<Map<String, Object>> repos = get(ctx, "/orgs/" + seg(ctx.org()) + "/repos?sort=pushed&per_page=" + repoCount, LIST);
        List<Future<List<WorkflowRun>>> futures = new ArrayList<>();
        for (Map<String, Object> r : repos) {
            if (Boolean.TRUE.equals(r.get("archived"))) continue;
            String name = (String) r.get("name");
            futures.add(executor.submit(() -> runs(ctx, name, null, null, 10)));
        }
        List<WorkflowRun> all = new ArrayList<>();
        for (Future<List<WorkflowRun>> f : futures) {
            try {
                all.addAll(f.get());
            } catch (Exception e) {
                // a repo without Actions access should not break the overview
            }
        }
        return all.stream().sorted(Comparator.comparing(WorkflowRun::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public void rerun(String repo, long runId, boolean failedOnly) {
        Ctx ctx = ctx();
        post(ctx, repoPath(ctx, repo) + "/actions/runs/" + runId + (failedOnly ? "/rerun-failed-jobs" : "/rerun"), Map.of());
    }

    @Override
    public void cancel(String repo, long runId) {
        Ctx ctx = ctx();
        post(ctx, repoPath(ctx, repo) + "/actions/runs/" + runId + "/cancel", Map.of());
    }

    // ------------------------------------------------------------------ teams

    @Override
    public List<Team> teams() {
        Ctx ctx = ctx();
        List<Map<String, Object>> teams = paged(ctx, "/orgs/" + seg(ctx.org()) + "/teams?", 5);
        List<Future<Team>> futures = teams.stream().map(t -> executor.submit(() -> {
            String slug = (String) t.get("slug");
            Integer count = null;
            try {
                Map<String, Object> detail = get(ctx, "/orgs/" + seg(ctx.org()) + "/teams/" + seg(slug), MAP);
                count = detail.get("members_count") instanceof Number n ? n.intValue() : null;
            } catch (ApiException ignored) {
                // count is optional
            }
            String parent = t.get("parent") instanceof Map<?, ?> p ? String.valueOf(p.get("name")) : null;
            return new Team(((Number) t.get("id")).longValue(), slug, (String) t.get("name"), (String) t.get("description"),
                    (String) t.get("privacy"), count, parent, (String) t.get("html_url"));
        })).toList();
        List<Team> result = new ArrayList<>();
        for (Future<Team> f : futures) {
            try {
                result.add(f.get());
            } catch (Exception e) {
                throw ApiException.upstream("Failed to load GitHub teams", e);
            }
        }
        return result.stream().sorted(Comparator.comparing(Team::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override
    public List<Member> teamMembers(String slug) {
        Ctx ctx = ctx();
        String base = "/orgs/" + seg(ctx.org()) + "/teams/" + seg(slug) + "/members?";
        Set<String> maintainers = paged(ctx, base + "role=maintainer&", 3).stream().map(m -> (String) m.get("login")).collect(Collectors.toSet());
        return paged(ctx, base + "role=all&", 10).stream()
                .map(m -> new Member((String) m.get("login"), (String) m.get("avatar_url"),
                        maintainers.contains((String) m.get("login")) ? "maintainer" : "member", (String) m.get("html_url"), "active"))
                .sorted(Comparator.comparing(Member::login, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    public List<Member> pendingInvitations(String slug) {
        Ctx ctx = ctx();
        return paged(ctx, "/orgs/" + seg(ctx.org()) + "/teams/" + seg(slug) + "/invitations?", 2).stream()
                .map(i -> new Member(i.get("login") == null ? (String) i.get("email") : (String) i.get("login"), null,
                        (String) i.get("role"), null, "pending"))
                .toList();
    }

    @Override
    public String addMember(String slug, String username, String role) {
        Ctx ctx = ctx();
        Map<String, Object> body = call(() -> ctx.client().put()
                .uri(ctx.uri("/orgs/" + seg(ctx.org()) + "/teams/" + seg(slug) + "/memberships/" + seg(username)))
                .body(Map.of("role", role == null ? "member" : role)).retrieve().body(MAP));
        return body == null ? "active" : String.valueOf(body.getOrDefault("state", "active"));
    }

    @Override
    public void removeMember(String slug, String username) {
        Ctx ctx = ctx();
        call(() -> ctx.client().delete()
                .uri(ctx.uri("/orgs/" + seg(ctx.org()) + "/teams/" + seg(slug) + "/memberships/" + seg(username)))
                .retrieve().toBodilessEntity());
    }

    @Override
    public List<Member> orgMembers() {
        Ctx ctx = ctx();
        return paged(ctx, "/orgs/" + seg(ctx.org()) + "/members?", 10).stream()
                .map(m -> new Member((String) m.get("login"), (String) m.get("avatar_url"), null, (String) m.get("html_url"), "active"))
                .sorted(Comparator.comparing(Member::login, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static WorkflowRun toRun(Map<String, Object> r, String repo) {
        Map<String, Object> actor = (Map<String, Object>) r.getOrDefault("actor", Map.of());
        String created = (String) r.get("run_started_at");
        if (created == null) created = (String) r.get("created_at");
        String updated = (String) r.get("updated_at");
        Long duration = null;
        if (created != null && updated != null && "completed".equals(r.get("status"))) {
            duration = Duration.between(Instant.parse(created), Instant.parse(updated)).toSeconds();
        }
        return new WorkflowRun(((Number) r.get("id")).longValue(), (String) r.get("name"), (String) r.get("display_title"), repo,
                (String) r.get("head_branch"), (String) r.get("event"), (String) r.get("status"), (String) r.get("conclusion"),
                (String) actor.get("login"), (String) actor.get("avatar_url"),
                r.get("run_number") instanceof Number n ? n.intValue() : 0,
                r.get("run_attempt") instanceof Number a ? a.intValue() : 1,
                (String) r.get("created_at"), updated, duration, (String) r.get("html_url"), (String) r.get("head_sha"));
    }

    private List<Map<String, Object>> paged(Ctx ctx, String pathWithQuery, int maxPages) {
        List<Map<String, Object>> all = new ArrayList<>();
        String sep = pathWithQuery.endsWith("?") || pathWithQuery.endsWith("&") ? "" : "&";
        for (int page = 1; page <= maxPages; page++) {
            List<Map<String, Object>> batch = get(ctx, pathWithQuery + sep + "per_page=100&page=" + page, LIST);
            if (batch == null || batch.isEmpty()) break;
            all.addAll(batch);
            if (batch.size() < 100) break;
        }
        return all;
    }

    private <T> T get(Ctx ctx, String path, ParameterizedTypeReference<T> type) {
        return call(() -> ctx.client().get().uri(ctx.uri(path)).retrieve().body(type));
    }

    private void post(Ctx ctx, String path, Object body) {
        call(() -> ctx.client().post().uri(ctx.uri(path)).body(body).retrieve().toBodilessEntity());
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String message = switch (code) {
                case 401 -> "GitHub token is invalid or expired - sign in again";
                case 403 -> "GitHub denied the request (missing scope or permission): " + Strings.truncate(e.getResponseBodyAsString(), 200);
                case 404 -> "GitHub resource not found (or no access)";
                case 422 -> "GitHub rejected the request: " + Strings.truncate(e.getResponseBodyAsString(), 300);
                default -> "GitHub returned " + code + ": " + Strings.truncate(e.getResponseBodyAsString(), 300);
            };
            HttpStatus status = code == 404 ? HttpStatus.NOT_FOUND : code == 403 ? HttpStatus.FORBIDDEN
                    : code == 422 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
            throw new ApiException(status, message, e);
        } catch (ResourceAccessException e) {
            throw ApiException.upstream("GitHub unreachable: " + e.getMessage(), e);
        }
    }

    private static String repoPath(Ctx ctx, String repo) {
        return "/repos/" + seg(ctx.org()) + "/" + seg(repo);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static String seg(String s) {
        return UriUtils.encodePathSegment(s, StandardCharsets.UTF_8);
    }

    private static String q(String s) {
        return UriUtils.encodeQueryParam(s, StandardCharsets.UTF_8);
    }
}
