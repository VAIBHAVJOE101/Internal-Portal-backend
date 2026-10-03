package com.platform.portal.boards;

import java.util.List;

public final class BoardsModels {

    private BoardsModels() {
    }

    public record Iteration(String id, String name, String path, String startDate, String finishDate, String timeFrame) {
    }

    public record Person(String displayName, String uniqueName, String imageUrl) {
    }

    public record WorkItem(int id, String title, String type, String state, Person assignedTo, String startDate, String endDate,
                           Double remainingWork, Double storyPoints, Integer priority, List<String> tags, Integer parentId,
                           String url, String changedDate, int rev) {
    }

    public record Sprint(Iteration iteration, List<String> columns, List<WorkItem> items, List<Iteration> iterations) {
    }

    /** Partial update; null fields are left unchanged, empty strings clear the field. */
    public record WorkItemUpdate(String state, String assignedTo, String startDate, String endDate, String title,
                                 Double remainingWork, Integer rev) {
    }
}
