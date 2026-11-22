package com.sdlcagent.orchestrator.governance;

/** A file change proposed by an agent. Nothing touches the workspace until policies and approvals pass. */
public record FileChange(String path, Op op, String content) {
    public enum Op { CREATE, MODIFY, DELETE }

    public static FileChange create(String path, String content) {
        return new FileChange(path, Op.CREATE, content);
    }

    public static FileChange modify(String path, String content) {
        return new FileChange(path, Op.MODIFY, content);
    }

    public static FileChange delete(String path) {
        return new FileChange(path, Op.DELETE, null);
    }
}
