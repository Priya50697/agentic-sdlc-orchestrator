package com.sdlcagent.orchestrator.engine;

public enum RunState {
    CREATED, RUNNING, WAITING_HUMAN, COMPLETED, SAFE_STOPPED, ABORTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == SAFE_STOPPED || this == ABORTED;
    }
}
