package com.platform.portal.github;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class GithubModels {

    private GithubModels() {
    }

    public record Repo(String name, String fullName, boolean privateRepo, String defaultBranch, String pushedAt, String htmlUrl,
                       String language, boolean archived) {
    }

    public record Workflow(long id, String name, String path, String state, String htmlUrl) {
    }

    public record WorkflowRun(long id, String name, String title, String repo, String branch, String event, String status,
                              String conclusion, String actor, String actorAvatar, int runNumber, int attempt,
                              String createdAt, String updatedAt, Long durationSeconds, String htmlUrl, String headSha) {
    }

    public record Team(long id, String slug, String name, String description, String privacy, Integer membersCount,
                       String parent, String htmlUrl) {
    }

    public record Member(String login, String avatarUrl, String role, String htmlUrl, String state) {
    }

    public record AddMemberRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,39}", message = "is not a valid GitHub username") String username,
                                   @Pattern(regexp = "member|maintainer") String role) {
    }
}
