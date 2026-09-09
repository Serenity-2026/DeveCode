


package com.agent.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录Agent进入某个worktree前的状态
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorktreeSession(
        //进入worktree前的原始工作目录
        @JsonProperty("original_cwd") String originalCwd,
        //当前worktree的路径
        @JsonProperty("worktree_path") String worktreePath,
        //worktree名称
        @JsonProperty("worktree_name") String worktreeName,
        //worktree所在分支
        @JsonProperty("worktree_branch") String worktreeBranch,
        //进入前所在分支
        @JsonProperty("original_branch") String originalBranch,
        //进入时的HEAD commit SHA
        @JsonProperty("original_head_commit") String originalHeadCommit,
        //会话id
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("creation_duration_ms") long creationDurationMs
) {}

