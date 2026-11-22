package com.sdlcagent.orchestrator.governance;

import java.time.Instant;

public record ApprovalRequest(
        String id, String runId, String nodeId, ApprovalKind kind, String summary, Instant createdAt,
        Status status, String feedback, Channel channel, String actor, Instant decidedAt, boolean applied) {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    public enum Decision { APPROVE, REJECT }

    public enum Channel { CONSOLE, REST, AUTO }

    public boolean isPending() {
        return status == Status.PENDING;
    }
}
