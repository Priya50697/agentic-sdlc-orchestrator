package com.sdlcagent.orchestrator.engine;

import java.util.ArrayList;
import java.util.List;

/** Pure function over the snapshot: which nodes may be dispatched now. Joins fall out of "all deps passed". */
public final class Scheduler {
    public List<Node> ready(RunSnapshot s) {
        List<Node> out = new ArrayList<>();
        for (Node n : s.nodes.values()) {
            boolean waiting = n.state == NodeState.PENDING || n.state == NodeState.RETRYING
                    || n.state == NodeState.INVALIDATED || n.state == NodeState.READY;
            if (!waiting) continue;
            boolean depsDone = true;
            for (String d : n.allDeps()) {
                Node dep = s.nodes.get(d);
                if (dep == null || !(dep.state == NodeState.PASSED || dep.state == NodeState.SKIPPED)) {
                    depsDone = false;
                    break;
                }
            }
            if (depsDone) out.add(n);
        }
        return out;
    }
}
