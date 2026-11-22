package com.sdlcagent.orchestrator.engine;

/** Rules only, no LLM. The engine enforces the budgets; the router only picks the direction. */
public class FailureRouter {
    public enum Action { RETRY_SAME, ROUTE_BACK, ESCALATE_HUMAN, SAFE_STOP }

    public record Decision(Action action, String targetNodeId, String reason) {}

    /** repeatedSignature: this exact failure (node + signature) has been seen before in this run. */
    public Decision route(String nodeId, FailureInfo f, boolean repeatedSignature) {
        return switch (f.kind()) {
            case COMPILE_TEST -> retry("test compile error: retry the test author");
            case COMPILE_MAIN -> back(nodeId, f, "main compile error: back to owning implementation task");
            case EXCEPTION_MAIN -> back(nodeId, f, "exception in main code: back to owning implementation task");
            case ASSERTION -> repeatedSignature
                    ? back(nodeId, f, "same assertion failed again: implementation defect")
                    : retry("first assertion failure: treat as test defect");
            case SPEC_GAP -> new Decision(Action.ESCALATE_HUMAN, null, "requirement gap needs a human");
            case PRECONDITION -> new Decision(Action.SAFE_STOP, null, "precondition failed: " + f.message());
            default -> retry("retry with feedback");
        };
    }

    private Decision retry(String why) {
        return new Decision(Action.RETRY_SAME, null, why);
    }

    private Decision back(String nodeId, FailureInfo f, String why) {
        String owner = f.ownerNodeId();
        if (owner == null || owner.equals(nodeId)) return retry(why + " (no separate owner, retrying in place)");
        return new Decision(Action.ROUTE_BACK, owner, why);
    }
}
