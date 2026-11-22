package com.sdlcagent.orchestrator.engine;

import com.sdlcagent.orchestrator.governance.ApprovalKind;
import com.sdlcagent.orchestrator.governance.FileChange;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One vertex of the run graph. Plain mutable fields so the whole graph serializes into state.json. */
public class Node {
    public String id;
    public NodeType type = NodeType.STAGE;
    public String agent;
    public List<String> dependsOn = new ArrayList<>();
    /** Dependencies added at runtime by an expander (e.g. the implementation join waits for the last wave). */
    public List<String> expandedDeps = new ArrayList<>();
    /** Artifact ids this node reads; their hashes at dispatch time drive invalidation. */
    public List<String> consumes = new ArrayList<>();
    public List<String> entryGates = new ArrayList<>();
    public List<String> exitGates = new ArrayList<>();
    public int retryBudget = 2;
    public NodeState state = NodeState.PENDING;
    public int attempts;
    public int revisions;
    public Map<String, String> inputHashes = new LinkedHashMap<>();
    public List<String> outputArtifactIds = new ArrayList<>();
    public String checkpointCommit;
    /** Human gate that always follows this node's exit gates (GATE_1, GATE_2), or null. */
    public ApprovalKind approvalGate;
    /** Name of the NodeExpander run when this node passes, or null. */
    public String expander;
    public String createdBy;
    public String specHash;
    /** Re-validate (INVALIDATED) whenever any dependency is not PASSED: used by joins and compile gates. */
    public boolean cascade;
    public Map<String, String> params = new LinkedHashMap<>();
    public List<String> feedback = new ArrayList<>();
    public List<FileChange> pendingChanges = new ArrayList<>();
    public List<ApprovalKind> pendingApprovals = new ArrayList<>();
    public String lastError;

    public Node() {}

    public Node(String id, NodeType type, String agent) {
        this.id = id;
        this.type = type;
        this.agent = agent;
    }

    public static Node stage(String id, String agent) {
        return new Node(id, NodeType.STAGE, agent);
    }

    public static Node task(String id, String agent) {
        return new Node(id, NodeType.TASK, agent);
    }

    public static Node join(String id) {
        Node n = new Node(id, NodeType.JOIN, null);
        n.cascade = true;
        return n;
    }

    public Node dependsOn(String... ids) {
        dependsOn.addAll(List.of(ids));
        return this;
    }

    public Node consumes(String... ids) {
        consumes.addAll(List.of(ids));
        return this;
    }

    public Node entry(String... gates) {
        entryGates.addAll(List.of(gates));
        return this;
    }

    public Node exit(String... gates) {
        exitGates.addAll(List.of(gates));
        return this;
    }

    public Node approval(ApprovalKind kind) {
        this.approvalGate = kind;
        return this;
    }

    public Node expanding(String expanderName) {
        this.expander = expanderName;
        return this;
    }

    public Node retries(int n) {
        this.retryBudget = n;
        return this;
    }

    public List<String> allDeps() {
        List<String> all = new ArrayList<>(dependsOn);
        all.addAll(expandedDeps);
        return all;
    }
}
