package com.sdlcagent.orchestrator.agents.architecture;

import com.sdlcagent.orchestrator.engine.TaskGraph.TaskSpec;
import java.util.List;

/**
 * The Architect's single reply: design (stored as the architecture-plan artifact) plus the task list
 * (stored as the task-graph artifact, which the TaskGraphExpander turns into nodes).
 */
public record ArchitecturePlan(String overview, List<Component> components, List<PlannedEndpoint> endpoints,
                               List<String> dataModel, List<TaskSpec> tasks) {

    /** layer is one of api, service, repository, model, config, client. */
    public record Component(String name, String layer, String path, String responsibility) {}

    public record PlannedEndpoint(String verb, String path, String description) {}

    public ArchitecturePlan {
        components = components == null ? List.of() : components;
        endpoints = endpoints == null ? List.of() : endpoints;
        dataModel = dataModel == null ? List.of() : dataModel;
        tasks = tasks == null ? List.of() : tasks;
    }

    /** The design without the tasks (the task graph is its own artifact). */
    public ArchitecturePlan withoutTasks() {
        return new ArchitecturePlan(overview, components, endpoints, dataModel, List.of());
    }
}
