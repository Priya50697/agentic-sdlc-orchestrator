package com.sdlcagent.orchestrator.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Hash-driven re-planning: a finished node recorded the hashes of the artifacts it consumed; if any of them
 * changed since, the node is INVALIDATED and re-runs. One mechanism covers clarification answers, gate
 * rejections and mid-run requirement changes.
 */
public final class Invalidator {
    public record Invalidation(String nodeId, String reason) {}

    public List<Invalidation> sweep(RunSnapshot s) {
        List<Invalidation> out = new ArrayList<>();
        for (Node n : s.nodes.values()) {
            if (n.state != NodeState.PASSED && n.state != NodeState.WAITING_APPROVAL) continue;
            for (String a : n.consumes) {
                if (!Objects.equals(s.artifactHashes.get(a), n.inputHashes.get(a))) {
                    invalidate(n, "input '" + a + "' changed", out);
                    break;
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Node n : s.nodes.values()) {
                if (!n.cascade || n.state != NodeState.PASSED) continue;
                for (String d : n.allDeps()) {
                    Node dep = s.nodes.get(d);
                    if (dep == null || !(dep.state == NodeState.PASSED || dep.state == NodeState.SKIPPED)) {
                        invalidate(n, "dependency '" + d + "' is no longer passed", out);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    private void invalidate(Node n, String reason, List<Invalidation> out) {
        n.state = NodeState.INVALIDATED;
        n.attempts = 0;
        n.lastError = reason;
        out.add(new Invalidation(n.id, reason));
    }

    /** Nodes that are downstream of {@code from} and upstream of {@code to}, excluding both ends. */
    public List<String> pathBetween(RunSnapshot s, String from, String to) {
        Set<String> down = reach(s, from, true);
        Set<String> up = reach(s, to, false);
        List<String> out = new ArrayList<>();
        for (String id : s.nodes.keySet()) {
            if (down.contains(id) && up.contains(id) && !id.equals(from) && !id.equals(to)) out.add(id);
        }
        return out;
    }

    private Set<String> reach(RunSnapshot s, String start, boolean downstream) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (!seen.add(cur)) continue;
            for (Node n : s.nodes.values()) {
                if (downstream && n.allDeps().contains(cur)) queue.add(n.id);
            }
            if (!downstream) {
                Node n = s.nodes.get(cur);
                if (n != null) queue.addAll(n.allDeps());
            }
        }
        return seen;
    }
}
