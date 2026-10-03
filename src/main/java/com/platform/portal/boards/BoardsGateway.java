package com.platform.portal.boards;

import java.util.List;

import com.platform.portal.boards.BoardsModels.Iteration;
import com.platform.portal.boards.BoardsModels.Person;
import com.platform.portal.boards.BoardsModels.WorkItem;
import com.platform.portal.boards.BoardsModels.WorkItemUpdate;

/** Azure Boards operations. Real implementation uses the Azure DevOps REST API (7.1). */
public interface BoardsGateway {

    List<Iteration> iterations();

    Iteration currentIteration();

    List<WorkItem> iterationItems(String iterationId);

    List<String> columns(List<WorkItem> items);

    List<Person> teamMembers();

    WorkItem get(int id);

    WorkItem update(int id, WorkItemUpdate update);

    List<BoardsModels.Comment> comments(int id);

    BoardsModels.Comment addComment(int id, String html);
}
