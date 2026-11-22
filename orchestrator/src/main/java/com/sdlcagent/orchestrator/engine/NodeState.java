package com.sdlcagent.orchestrator.engine;

public enum NodeState {
    PENDING, READY, RUNNING, VALIDATING, PASSED, FAILED, RETRYING, ROLLED_BACK, WAITING_APPROVAL, SKIPPED, INVALIDATED
}
