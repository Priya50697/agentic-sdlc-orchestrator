package com.sdlcagent.orchestrator.governance;

import com.sdlcagent.orchestrator.governance.ApprovalRequest.Channel;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Decision;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Status;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One queue of decisions per run. Console and REST both resolve through here (thread-safe); the engine drains
 * resolved-but-unapplied decisions on its own thread. Every resolution is audited as HUMAN_APPROVAL.
 */
public class HumanApprovalManager {
    private final String runId;
    private final AuditLogger audit;
    private final Clock clock;
    private final Map<String, ApprovalRequest> byId = new LinkedHashMap<>();
    private int counter;
    private volatile Runnable wakeup = () -> { };

    public HumanApprovalManager(String runId, AuditLogger audit, Clock clock) {
        this.runId = runId;
        this.audit = audit;
        this.clock = clock;
    }

    public void setWakeup(Runnable wakeup) {
        this.wakeup = wakeup;
    }

    public synchronized ApprovalRequest request(String nodeId, ApprovalKind kind, String summary) {
        String id = "ap-" + (++counter);
        ApprovalRequest r = new ApprovalRequest(id, runId, nodeId, kind, summary, clock.instant(),
                Status.PENDING, null, null, null, null, false);
        byId.put(id, r);
        return r;
    }

    public ApprovalRequest resolve(String id, Decision decision, String feedback, Channel channel, String actor) {
        ApprovalRequest done;
        synchronized (this) {
            ApprovalRequest r = byId.get(id);
            if (r == null) throw new IllegalArgumentException("unknown approval " + id);
            if (!r.isPending()) throw new IllegalStateException("approval " + id + " already " + r.status());
            done = new ApprovalRequest(r.id(), r.runId(), r.nodeId(), r.kind(), r.summary(), r.createdAt(),
                    decision == Decision.APPROVE ? Status.APPROVED : Status.REJECTED,
                    feedback, channel, actor, clock.instant(), false);
            byId.put(id, done);
            audit.log(AuditEventType.HUMAN_APPROVAL, r.nodeId(), AuditLogger.data(
                    "requestId", id, "kind", r.kind().name(), "decision", decision.name(),
                    "channel", channel == null ? null : channel.name(), "actor", actor, "feedback", feedback));
        }
        wakeup.run();
        return done;
    }

    /** Withdraws a pending request (its node was invalidated while waiting). */
    public synchronized void cancel(String nodeId) {
        for (ApprovalRequest r : List.copyOf(byId.values())) {
            if (r.nodeId().equals(nodeId) && r.isPending()) {
                byId.put(r.id(), new ApprovalRequest(r.id(), r.runId(), r.nodeId(), r.kind(), r.summary(),
                        r.createdAt(), Status.CANCELLED, r.feedback(), r.channel(), r.actor(), clock.instant(), true));
            }
        }
    }

    public synchronized List<ApprovalRequest> pending() {
        return byId.values().stream().filter(ApprovalRequest::isPending).toList();
    }

    public synchronized List<ApprovalRequest> all() {
        return List.copyOf(byId.values());
    }

    public synchronized boolean hasUnapplied() {
        return byId.values().stream().anyMatch(r -> !r.isPending() && !r.applied());
    }

    /** Returns resolved decisions the engine has not acted on yet, and marks them applied. */
    public synchronized List<ApprovalRequest> drainResolved() {
        List<ApprovalRequest> out = new ArrayList<>();
        for (ApprovalRequest r : List.copyOf(byId.values())) {
            if (!r.isPending() && !r.applied()) {
                out.add(r);
                byId.put(r.id(), new ApprovalRequest(r.id(), r.runId(), r.nodeId(), r.kind(), r.summary(),
                        r.createdAt(), r.status(), r.feedback(), r.channel(), r.actor(), r.decidedAt(), true));
            }
        }
        return out;
    }

    public synchronized void restore(List<ApprovalRequest> saved) {
        byId.clear();
        for (ApprovalRequest r : saved) byId.put(r.id(), r);
        counter = saved.size();
    }
}
