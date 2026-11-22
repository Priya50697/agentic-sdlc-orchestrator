package com.sdlcagent.orchestrator.engine;

/** Workspace checkpoints (git in P3). commit ids are opaque to the engine. */
public interface CheckpointService {
    /** Snapshot the workspace; returns a commit id, or null if nothing to checkpoint. */
    String checkpoint(String label);

    void rollbackTo(String commit);

    CheckpointService NOOP = new CheckpointService() {
        @Override public String checkpoint(String label) { return null; }
        @Override public void rollbackTo(String commit) { }
    };
}
