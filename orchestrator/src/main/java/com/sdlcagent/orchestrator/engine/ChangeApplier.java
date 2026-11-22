package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.FileChange;
import java.util.List;

/** Applies vetted changes to the workspace (SandboxedFileWriter in P3). Called only after policies/approvals pass. */
@FunctionalInterface
public interface ChangeApplier {
    void apply(String runId, String nodeId, List<FileChange> changes);

    ChangeApplier NOOP = (runId, nodeId, changes) -> { };
}
