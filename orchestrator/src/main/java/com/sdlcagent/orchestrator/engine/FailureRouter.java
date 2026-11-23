package com.sdlcagent.orchestrator.engine;

/** Rules only, no LLM. The engine enforces the budgets; the router only picks the direction. */
public class FailureRouter {
    public enum Action { RETRY_SAME, ROUTE_BACK, ESCALATE_HUMAN, SAFE_STOP }

    public record Decision(Action action, String targetNodeId, String reason) {}

    /** repeatedSignature: this exact failure (node + signature) has been seen before in this run. */
    public Decision route(String nodeId, FailureInfo f, boolean repeatedSignature) {
        return route(nodeId, f, repeatedSignature ? 2 : 1);
    }

    /** Same failure this many times in a row seen for a code defect: another identical fix attempt will not help. */
    public static final int MAX_SAME_CODE_FAILURE = 3;

    /** timesSeen: how often this exact failure (node + signature) has occurred in this run, including now. */
    public Decision route(String nodeId, FailureInfo f, int timesSeen) {
        boolean repeatedSignature = timesSeen > 1;
        boolean codeDefect = f.kind() == FailureKind.COMPILE_MAIN || f.kind() == FailureKind.EXCEPTION_MAIN;
        if (codeDefect && timesSeen >= MAX_SAME_CODE_FAILURE) {
            return new Decision(Action.SAFE_STOP, null, "same " + f.kind() + " failure at "
                    + f.signature() + " repeated " + timesSeen + " times: not converging, stopping early");
        }
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
