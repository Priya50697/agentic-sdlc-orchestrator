package com.sdlcagent.orchestrator.agents.architecture;

import com.sdlcagent.orchestrator.agents.base.ExpanderBean;
import com.sdlcagent.orchestrator.engine.Node;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.engine.TaskGraph;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Turns the approved task graph into impl:&lt;task&gt; nodes and one compile gate per wave. */
@Component
public class TaskGraphExpander implements ExpanderBean {
    @Override
    public String name() {
        return StageGraph.TASK_GRAPH_EXPANDER;
    }

    @Override
    public Expansion expand(Node owner, Map<String, String> outputs) {
        String json = outputs.get(StageGraph.TASK_GRAPH);
        if (json == null) throw new IllegalArgumentException("architect produced no task-graph artifact");
        return TaskGraph.parse(json).toExpansion(owner.id, "implementer", "compile-gate", "implementation",
                List.of(StageGraph.SPEC, StageGraph.PLAN, StageGraph.TASK_GRAPH));
    }
}
