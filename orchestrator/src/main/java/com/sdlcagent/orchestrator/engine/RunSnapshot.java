package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything needed to resume a run; persisted via RunRepository after every transition. */
public class RunSnapshot {
    public String runId;
    public String scenario;
    public RunState state = RunState.CREATED;
    public Map<String, Node> nodes = new LinkedHashMap<>();
    public Map<String, String> artifactHashes = new LinkedHashMap<>();
    public int llmCalls;
    public int backEdges;
    public String lastGoodCheckpoint;
    public String stopReason;
    public List<ApprovalRequest> approvals = new ArrayList<>();
    /** "nodeId|signature" -> occurrences, used to tell a repeated failure from a first one. */
    public Map<String, Integer> failureSignatures = new LinkedHashMap<>();
}
