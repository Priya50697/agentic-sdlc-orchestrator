package com.sdlcagent.orchestrator.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Architect's runtime task graph. Tasks without mutual dependencies share a wave and run in parallel;
 * one compile-gate node closes each wave.
 */
public class TaskGraph {
    public record TaskSpec(String id, String description, List<String> dependsOn,
                           List<String> targetPaths, List<String> acceptanceCriteria) {
        public TaskSpec {
            dependsOn = dependsOn == null ? List.of() : dependsOn;
            targetPaths = targetPaths == null ? List.of() : targetPaths;
            acceptanceCriteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final List<TaskSpec> tasks;

    public TaskGraph(List<TaskSpec> tasks) {
        this.tasks = List.copyOf(tasks);
    }

    /** Parses {"tasks":[{id, description, dependsOn[], targetPaths[], acceptanceCriteria[]}]}. */
    public static TaskGraph parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.path("tasks");
            if (!arr.isArray()) throw new IllegalArgumentException("task graph has no 'tasks' array");
            List<TaskSpec> list = new ArrayList<>();
            for (JsonNode t : arr) {
                list.add(MAPPER.treeToValue(t, TaskSpec.class));
            }
            return new TaskGraph(list);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("unparseable task graph: " + e.getMessage(), e);
        }
    }

    public List<TaskSpec> tasks() {
        return tasks;
    }

    /** Structural errors: empty, duplicate ids, unknown dependencies, cycles. Empty list = valid. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (tasks.isEmpty()) errors.add("task graph is empty");
        Set<String> ids = new java.util.HashSet<>();
        for (TaskSpec t : tasks) {
            if (t.id() == null || t.id().isBlank()) errors.add("task without id");
            else if (!ids.add(t.id())) errors.add("duplicate task id " + t.id());
        }
        for (TaskSpec t : tasks) {
            for (String d : t.dependsOn()) {
                if (!ids.contains(d)) errors.add("task " + t.id() + " depends on unknown task " + d);
            }
        }
        if (errors.isEmpty() && waves().stream().mapToInt(List::size).sum() != tasks.size()) {
            errors.add("task graph contains a cycle");
        }
        return errors;
    }

    /** Kahn levels. Tasks stuck in a cycle are omitted (validate() detects that). */
    public List<List<TaskSpec>> waves() {
        Map<String, Integer> level = new HashMap<>();
        Map<String, TaskSpec> byId = new LinkedHashMap<>();
        tasks.forEach(t -> byId.put(t.id(), t));
        boolean progress = true;
        while (progress) {
            progress = false;
            for (TaskSpec t : tasks) {
                if (level.containsKey(t.id())) continue;
                int lvl = 0;
                boolean ok = true;
                for (String d : t.dependsOn()) {
                    Integer dl = level.get(d);
                    if (dl == null) { ok = false; break; }
                    lvl = Math.max(lvl, dl + 1);
                }
                if (ok) {
                    level.put(t.id(), lvl);
                    progress = true;
                }
            }
        }
        int max = level.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        List<List<TaskSpec>> waves = new ArrayList<>();
        for (int i = 0; i <= max; i++) waves.add(new ArrayList<>());
        for (TaskSpec t : tasks) {
            Integer l = level.get(t.id());
            if (l != null) waves.get(l).add(t);
        }
        return waves;
    }

    /**
     * Builds task nodes (id "impl:&lt;taskId&gt;") and one compile-gate node per wave ("wave:&lt;n&gt;"). Wave n tasks wait on
     * wave n-1's gate. spec hashes let a re-expansion keep nodes whose definition did not change.
     */
    public NodeExpander.Expansion toExpansion(String ownerId, String taskAgent, String waveGateAgent,
                                              String joinNodeId, List<String> taskConsumes) {
        List<Node> nodes = new ArrayList<>();
        List<List<TaskSpec>> waves = waves();
        String prevGate = null;
        for (int w = 0; w < waves.size(); w++) {
            List<String> taskIds = new ArrayList<>();
            for (TaskSpec t : waves.get(w)) {
                Node n = Node.task("impl:" + t.id(), taskAgent).dependsOn(ownerId);
                for (String d : t.dependsOn()) n.dependsOn("impl:" + d);
                if (prevGate != null) n.dependsOn(prevGate);
                n.consumes.addAll(taskConsumes);
                n.params.put("taskId", t.id());
                n.params.put("description", t.description() == null ? "" : t.description());
                n.params.put("targetPaths", String.join(",", t.targetPaths()));
                n.params.put("acceptanceCriteria", String.join(",", t.acceptanceCriteria()));
                n.specHash = specHash(n);
                n.createdBy = ownerId;
                nodes.add(n);
                taskIds.add(n.id);
            }
            Node gate = Node.task("wave:" + w, waveGateAgent);
            gate.cascade = true;
            taskIds.forEach(gate::dependsOn);
            gate.params.put("wave", String.valueOf(w));
            gate.specHash = specHash(gate);
            gate.createdBy = ownerId;
            nodes.add(gate);
            prevGate = gate.id;
        }
        Map<String, List<String>> extra = prevGate == null ? Map.of() : Map.of(joinNodeId, List.of(prevGate));
        return new NodeExpander.Expansion(nodes, extra);
    }

    private static String specHash(Node n) {
        String raw = n.agent + "|" + n.dependsOn + "|" + n.params;
        return Hashing.sha256(raw);
    }

    @Override
    public String toString() {
        return tasks.stream().map(TaskSpec::id).collect(Collectors.joining(","));
    }
}
