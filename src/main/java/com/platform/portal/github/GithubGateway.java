package com.platform.portal.github;

import java.util.List;

import com.platform.portal.github.GithubModels.Member;
import com.platform.portal.github.GithubModels.Repo;
import com.platform.portal.github.GithubModels.Team;
import com.platform.portal.github.GithubModels.Workflow;
import com.platform.portal.github.GithubModels.WorkflowRun;

public interface GithubGateway {

    String org();

    List<Repo> repos();

    List<Workflow> workflows(String repo);

    List<WorkflowRun> runs(String repo, String branch, String status, int limit);

    /** Latest runs across the most recently pushed repositories. */
    List<WorkflowRun> recentRuns(int repoCount, int limit);

    void rerun(String repo, long runId, boolean failedOnly);

    void cancel(String repo, long runId);

    List<Team> teams();

    List<Member> teamMembers(String slug);

    List<Member> pendingInvitations(String slug);

    /** Adds (or invites) the user; returns the membership state ("active" or "pending"). */
    String addMember(String slug, String username, String role);

    void removeMember(String slug, String username);

    List<Member> orgMembers();
}
