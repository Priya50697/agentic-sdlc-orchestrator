package com.sdlcagent.orchestrator.governance;

/**
 * GATE_x: human reviews the node's output (approve = proceed, reject + feedback = rerun the node).
 * RISK_ACTION: a HIGH-risk change needs sign-off (reject = attempt fails). SPEC_GAP: an agent flagged a
 * requirement gap (approve + feedback = rerun with the clarification, reject = safe-stop).
 */
public enum ApprovalKind { GATE_0, GATE_1, GATE_2, RISK_ACTION, SPEC_GAP }
