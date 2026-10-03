package com.platform.portal.boards;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.platform.portal.audit.AuditService;
import com.platform.portal.boards.BoardsModels.Iteration;
import com.platform.portal.boards.BoardsModels.Person;
import com.platform.portal.boards.BoardsModels.Sprint;
import com.platform.portal.boards.BoardsModels.WorkItem;
import com.platform.portal.boards.BoardsModels.WorkItemUpdate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.util.HtmlUtils;
import com.platform.portal.common.Strings;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/boards")
public class BoardsController {

    private final BoardsGateway boards;
    private final AuditService audit;

    public BoardsController(BoardsGateway boards, AuditService audit) {
        this.boards = boards;
        this.audit = audit;
    }

    /** Current sprint (or the given iteration) with its work items and board columns. */
    @GetMapping("/sprint")
    public Sprint sprint(@RequestParam(required = false) String iterationId) {
        List<Iteration> all = boards.iterations();
        Iteration iteration = iterationId == null ? boards.currentIteration()
                : all.stream().filter(i -> i.id().equals(iterationId)).findFirst().orElseGet(boards::currentIteration);
        List<WorkItem> items = boards.iterationItems(iteration.id());
        return new Sprint(iteration, boards.columns(items), items, all);
    }

    @GetMapping("/team")
    public List<Person> team() {
        return boards.teamMembers();
    }

    @GetMapping("/work-items/{id}")
    public WorkItem get(@PathVariable int id) {
        return boards.get(id);
    }

    @PatchMapping("/work-items/{id}")
    public WorkItem update(@PathVariable int id, @RequestBody WorkItemUpdate update) {
        WorkItem before = boards.get(id);
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        diff(b, a, "state", before.state(), update.state());
        diff(b, a, "assignedTo", before.assignedTo() == null ? null : before.assignedTo().uniqueName(), update.assignedTo());
        diff(b, a, "startDate", before.startDate(), update.startDate());
        diff(b, a, "endDate", before.endDate(), update.endDate());
        diff(b, a, "title", before.title(), update.title());
        if (update.description() != null && !update.description().equals(before.description())) {
            b.put("description", Strings.truncate(before.description(), 300));
            a.put("description", Strings.truncate(update.description(), 300));
        }
        return audit.track("BOARDS_WORKITEM_UPDATE", "work-item", "#" + id + " " + before.title(), Map.of("before", b, "after", a),
                () -> boards.update(id, update));
    }

    @GetMapping("/work-items/{id}/comments")
    public List<BoardsModels.Comment> comments(@PathVariable int id) {
        return boards.comments(id);
    }

    /** Plain-text comment; converted to escaped HTML with line breaks for Azure DevOps. */
    @PostMapping("/work-items/{id}/comments")
    public BoardsModels.Comment addComment(@PathVariable int id, @Valid @RequestBody BoardsModels.CommentRequest request) {
        String html = "<div>" + HtmlUtils.htmlEscape(request.text().trim()).replace("\n", "<br>") + "</div>";
        return audit.track("BOARDS_COMMENT_ADD", "work-item", "#" + id, Map.of("text", Strings.truncate(request.text(), 500)),
                () -> boards.addComment(id, html));
    }

    private static void diff(Map<String, Object> before, Map<String, Object> after, String key, Object oldValue, Object newValue) {
        if (newValue != null && !Objects.equals(oldValue, newValue)) {
            before.put(key, oldValue);
            after.put(key, newValue);
        }
    }
}
