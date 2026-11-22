package com.sdlcagent.orchestrator.governance;

import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import java.util.List;

public record GateResult(Status status, List<String> errors, FailureInfo failure,
                         ApprovalKind approvalKind, String approvalSummary) {
    public enum Status { PASS, FAIL, NEEDS_APPROVAL }

    public static GateResult pass() {
        return new GateResult(Status.PASS, List.of(), null, null, null);
    }

    public static GateResult fail(FailureInfo failure) {
        return new GateResult(Status.FAIL, List.of(failure.message()), failure, null, null);
    }

    public static GateResult fail(String... errors) {
        String msg = String.join("; ", errors);
        return new GateResult(Status.FAIL, List.of(errors), FailureInfo.of(FailureKind.OTHER, msg), null, null);
    }

    public static GateResult needsApproval(ApprovalKind kind, String summary) {
        return new GateResult(Status.NEEDS_APPROVAL, List.of(), null, kind, summary);
    }
}
