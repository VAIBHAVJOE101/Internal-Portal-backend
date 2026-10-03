package com.platform.portal.boards;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.platform.portal.boards.BoardsModels.Iteration;
import com.platform.portal.boards.BoardsModels.Person;
import com.platform.portal.boards.BoardsModels.WorkItem;
import com.platform.portal.boards.BoardsModels.WorkItemUpdate;
import com.platform.portal.common.ApiException;
import com.platform.portal.common.Strings;
import com.platform.portal.settings.IntegrationProbe;
import com.platform.portal.settings.SettingType;
import com.platform.portal.settings.SettingsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

/** Azure DevOps Boards REST client (api-version 7.1) authenticated with a PAT from Settings. */
@Component
@ConditionalOnProperty(name = "portal.mode", havingValue = "real", matchIfMissing = true)
public class AzureDevOpsGateway implements BoardsGateway, IntegrationProbe {

    private static final String API = "api-version=7.1";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };
    private static final List<String> FIELDS = List.of("System.Id", "System.Title", "System.WorkItemType", "System.State",
            "System.AssignedTo", "System.Tags", "System.Parent", "System.ChangedDate", "Microsoft.VSTS.Common.Priority",
            "Microsoft.VSTS.Scheduling.RemainingWork", "Microsoft.VSTS.Scheduling.StoryPoints", "System.CommentCount");
    private static final String COMMENTS_API = "api-version=7.1-preview.4";

    private final SettingsService settings;
    private final SimpleClientHttpRequestFactory requestFactory;

    public AzureDevOpsGateway(SettingsService settings) {
        this.settings = settings;
        this.requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
    }

    private record Ctx(RestClient client, String baseUrl, String org, String project, String team, Map<String, String> cfg) {
        java.net.URI uri(String path) {
            return java.net.URI.create(baseUrl + path);
        }

        String projectBase() {
            return "/" + seg(org) + "/" + seg(project);
        }

        String teamBase() {
            return projectBase() + "/" + seg(team);
        }

        String startField() {
            return cfg.getOrDefault("startDateField", "Microsoft.VSTS.Scheduling.StartDate");
        }

        String endField() {
            return cfg.getOrDefault("endDateField", "Microsoft.VSTS.Scheduling.FinishDate");
        }
    }

    private Ctx ctx() {
        return ctx(settings.resolve(SettingType.AZURE_DEVOPS));
    }

    private Ctx ctx(Map<String, String> cfg) {
        String pat = settings.require(cfg, "pat", SettingType.AZURE_DEVOPS);
        String org = settings.require(cfg, "organization", SettingType.AZURE_DEVOPS);
        String project = settings.require(cfg, "project", SettingType.AZURE_DEVOPS);
        String team = settings.require(cfg, "team", SettingType.AZURE_DEVOPS);
        String auth = Base64.getEncoder().encodeToString((":" + pat).getBytes(StandardCharsets.UTF_8));
        RestClient client = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Basic " + auth)
                .defaultHeader("Accept", "application/json")
                .build();
        String baseUrl = cfg.getOrDefault("baseUrl", "https://dev.azure.com").replaceAll("/+$", "");
        return new Ctx(client, baseUrl, org, project, team, cfg);
    }

    @Override
    public SettingType type() {
        return SettingType.AZURE_DEVOPS;
    }

    @Override
    public Map<String, Object> probe(Map<String, String> cfg) {
        Ctx ctx = ctx(cfg);
        Map<String, Object> current = get(ctx, ctx.teamBase() + "/_apis/work/teamsettings/iterations?$timeframe=current&" + API);
        List<Map<String, Object>> value = list(current.get("value"));
        return Map.of("team", ctx.team(), "currentSprint", value.isEmpty() ? "none" : String.valueOf(value.getFirst().get("name")));
    }

    @Override
    public List<Iteration> iterations() {
        Ctx ctx = ctx();
        Map<String, Object> body = get(ctx, ctx.teamBase() + "/_apis/work/teamsettings/iterations?" + API);
        return list(body.get("value")).stream().map(AzureDevOpsGateway::toIteration).toList();
    }

    @Override
    public Iteration currentIteration() {
        Ctx ctx = ctx();
        Map<String, Object> body = get(ctx, ctx.teamBase() + "/_apis/work/teamsettings/iterations?$timeframe=current&" + API);
        List<Map<String, Object>> value = list(body.get("value"));
        if (value.isEmpty()) {
            throw ApiException.notFound("Current sprint for team " + ctx.team());
        }
        return toIteration(value.getFirst());
    }

    @Override
    public List<WorkItem> iterationItems(String iterationId) {
        Ctx ctx = ctx();
        Map<String, Object> rel = get(ctx, ctx.teamBase() + "/_apis/work/teamsettings/iterations/" + seg(iterationId) + "/workitems?" + API);
        Set<Integer> ids = new LinkedHashSet<>();
        for (Map<String, Object> r : list(rel.get("workItemRelations"))) {
            if (r.get("target") instanceof Map<?, ?> target && target.get("id") instanceof Number n) {
                ids.add(n.intValue());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<WorkItem> items = new ArrayList<>();
        List<Integer> all = new ArrayList<>(ids);
        List<String> fields = new ArrayList<>(FIELDS);
        fields.add(ctx.startField());
        fields.add(ctx.endField());
        for (int i = 0; i < all.size(); i += 200) {
            Map<String, Object> body = Map.of("ids", all.subList(i, Math.min(i + 200, all.size())), "fields", fields, "errorPolicy", "omit");
            Map<String, Object> batch = call(() -> ctx.client().post().uri(ctx.uri(ctx.projectBase() + "/_apis/wit/workitemsbatch?" + API))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP));
            for (Map<String, Object> wi : list(batch.get("value"))) {
                if (wi != null) items.add(toWorkItem(wi, ctx));
            }
        }
        items.sort(Comparator.comparing((WorkItem w) -> w.priority() == null ? 99 : w.priority()).thenComparingInt(WorkItem::id));
        return items;
    }

    @Override
    public List<String> columns(List<WorkItem> items) {
        Map<String, String> cfg = settings.resolve(SettingType.AZURE_DEVOPS);
        LinkedHashSet<String> cols = new LinkedHashSet<>(Strings.asList(cfg.getOrDefault("boardColumns", "New,Active,Resolved,Closed")));
        items.stream().map(WorkItem::state).forEach(cols::add);
        return List.copyOf(cols);
    }

    @Override
    public List<Person> teamMembers() {
        Ctx ctx = ctx();
        Map<String, Object> body = get(ctx, "/" + seg(ctx.org()) + "/_apis/projects/" + seg(ctx.project()) + "/teams/" + seg(ctx.team())
                + "/members?$top=500&" + API);
        return list(body.get("value")).stream().map(m -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> identity = (Map<String, Object>) m.get("identity");
            return new Person((String) identity.get("displayName"), (String) identity.get("uniqueName"), (String) identity.get("imageUrl"));
        }).sorted(Comparator.comparing(Person::displayName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override
    public WorkItem get(int id) {
        Ctx ctx = ctx();
        return toWorkItem(get(ctx, ctx.projectBase() + "/_apis/wit/workitems/" + id + "?" + API), ctx);
    }

    @Override
    public WorkItem update(int id, WorkItemUpdate update) {
        Ctx ctx = ctx();
        List<Map<String, Object>> patch = new ArrayList<>();
        if (update.rev() != null) {
            patch.add(Map.of("op", "test", "path", "/rev", "value", update.rev()));
        }
        field(patch, "System.State", update.state());
        field(patch, "System.Title", update.title());
        field(patch, "System.AssignedTo", update.assignedTo());
        field(patch, ctx.startField(), update.startDate());
        field(patch, ctx.endField(), update.endDate());
        if (update.description() != null) {
            field(patch, descriptionField(ctx, id), update.description());
        }
        if (update.remainingWork() != null) {
            patch.add(op("Microsoft.VSTS.Scheduling.RemainingWork", update.remainingWork()));
        }
        if (patch.size() <= (update.rev() == null ? 0 : 1)) {
            return get(id);
        }
        Map<String, Object> body = call(() -> ctx.client().patch().uri(ctx.uri(ctx.projectBase() + "/_apis/wit/workitems/" + id + "?" + API))
                .contentType(MediaType.parseMediaType("application/json-patch+json")).body(patch).retrieve().body(MAP));
        return toWorkItem(body, ctx);
    }

    @Override
    public List<BoardsModels.Comment> comments(int id) {
        Ctx ctx = ctx();
        Map<String, Object> body = get(ctx, ctx.projectBase() + "/_apis/wit/workItems/" + id + "/comments?$top=200&order=asc&" + COMMENTS_API);
        return list(body.get("comments")).stream().map(AzureDevOpsGateway::toComment).toList();
    }

    @Override
    public BoardsModels.Comment addComment(int id, String html) {
        Ctx ctx = ctx();
        Map<String, Object> body = call(() -> ctx.client().post().uri(ctx.uri(ctx.projectBase() + "/_apis/wit/workItems/" + id + "/comments?" + COMMENTS_API))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("text", html)).retrieve().body(MAP));
        return toComment(body);
    }

    /** Bugs usually carry their description in Repro Steps; edit that field when System.Description is empty. */
    private String descriptionField(Ctx ctx, int id) {
        @SuppressWarnings("unchecked")
        Map<String, Object> f = (Map<String, Object>) get(ctx, ctx.projectBase() + "/_apis/wit/workitems/" + id + "?" + API).getOrDefault("fields", Map.of());
        boolean useRepro = "Bug".equals(f.get("System.WorkItemType")) && Strings.isBlank((String) f.get("System.Description"))
                && !Strings.isBlank((String) f.get("Microsoft.VSTS.TCM.ReproSteps"));
        return useRepro ? "Microsoft.VSTS.TCM.ReproSteps" : "System.Description";
    }

    private static String description(Map<String, Object> f) {
        String d = (String) f.get("System.Description");
        return Strings.isBlank(d) ? (String) f.get("Microsoft.VSTS.TCM.ReproSteps") : d;
    }

    @SuppressWarnings("unchecked")
    private static BoardsModels.Comment toComment(Map<String, Object> c) {
        Map<String, Object> by = (Map<String, Object>) c.getOrDefault("createdBy", Map.of());
        return new BoardsModels.Comment(c.get("id") instanceof Number n ? n.intValue() : 0, (String) c.get("text"),
                new Person((String) by.get("displayName"), (String) by.get("uniqueName"), (String) by.get("imageUrl")),
                (String) c.get("createdDate"), (String) c.get("modifiedDate"));
    }

    private static void field(List<Map<String, Object>> patch, String field, String value) {
        if (value == null) return;
        if (value.isEmpty()) {
            patch.add(Map.of("op", "remove", "path", "/fields/" + field));
        } else {
            patch.add(op(field, value));
        }
    }

    private static Map<String, Object> op(String field, Object value) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("path", "/fields/" + field);
        op.put("value", value);
        return op;
    }

    private Map<String, Object> get(Ctx ctx, String uri) {
        return call(() -> ctx.client().get().uri(ctx.uri(uri)).retrieve().body(MAP));
    }

    private static Map<String, Object> call(java.util.function.Supplier<Map<String, Object>> request) {
        try {
            Map<String, Object> body = request.get();
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            HttpStatus status = e.getStatusCode().value() == 401 || e.getStatusCode().value() == 203
                    ? HttpStatus.FAILED_DEPENDENCY : HttpStatus.BAD_GATEWAY;
            String detail = e.getStatusCode().value() == 401 ? "PAT rejected - check the Azure DevOps token in Settings"
                    : Strings.truncate(e.getResponseBodyAsString(), 400);
            throw new ApiException(status, "Azure DevOps: " + detail, e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw ApiException.upstream("Azure DevOps unreachable: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Iteration toIteration(Map<String, Object> m) {
        Map<String, Object> attrs = (Map<String, Object>) m.getOrDefault("attributes", Map.of());
        return new Iteration(String.valueOf(m.get("id")), String.valueOf(m.get("name")), String.valueOf(m.get("path")),
                (String) attrs.get("startDate"), (String) attrs.get("finishDate"), (String) attrs.get("timeFrame"));
    }

    @SuppressWarnings("unchecked")
    private static WorkItem toWorkItem(Map<String, Object> wi, Ctx ctx) {
        Map<String, Object> f = (Map<String, Object>) wi.getOrDefault("fields", Map.of());
        Person assignee = null;
        if (f.get("System.AssignedTo") instanceof Map<?, ?> a) {
            assignee = new Person((String) a.get("displayName"), (String) a.get("uniqueName"), (String) a.get("imageUrl"));
        }
        String tags = (String) f.get("System.Tags");
        int id = ((Number) wi.get("id")).intValue();
        String url = ctx.baseUrl() + ctx.projectBase()
                + "/_workitems/edit/" + id;
        return new WorkItem(id, (String) f.get("System.Title"), (String) f.get("System.WorkItemType"), (String) f.get("System.State"),
                assignee, (String) f.get(ctx.startField()), (String) f.get(ctx.endField()), num(f.get("Microsoft.VSTS.Scheduling.RemainingWork")),
                num(f.get("Microsoft.VSTS.Scheduling.StoryPoints")),
                f.get("Microsoft.VSTS.Common.Priority") instanceof Number p ? p.intValue() : null,
                tags == null ? List.of() : Strings.asList(tags.replace(';', ',')),
                f.get("System.Parent") instanceof Number p ? p.intValue() : null,
                url, (String) f.get("System.ChangedDate"), wi.get("rev") instanceof Number r ? r.intValue() : 0,
                description(f), (String) f.get("Microsoft.VSTS.Common.AcceptanceCriteria"),
                f.get("System.CommentCount") instanceof Number c ? c.intValue() : null);
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String seg(String s) {
        return UriUtils.encodePathSegment(s, StandardCharsets.UTF_8);
    }
}
