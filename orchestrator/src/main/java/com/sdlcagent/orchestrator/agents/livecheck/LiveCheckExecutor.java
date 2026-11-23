package com.sdlcagent.orchestrator.agents.livecheck;

import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.base.SdlcAgent;
import com.sdlcagent.orchestrator.engine.NodeContext;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Advisory, non-LLM stage: boots the generated app and probes its endpoints. It never fails the run; the report
 * goes to the release reviewer as evidence. To make it blocking later, return NodeResult.failed here and give the
 * stage an exit gate in {@link StageGraph}.
 */
@Component
public class LiveCheckExecutor implements SdlcAgent {
    private final LiveAppRunner runner;

    public LiveCheckExecutor(LiveAppRunner runner) {
        this.runner = runner;
    }

    @Override
    public String key() {
        return "live-check";
    }

    @Override
    public NodeResult run(NodeContext ctx) {
        LiveReport report;
        try (LiveAppRunner.Running app = runner.start()) {
            report = new LiveReport(true, null, LiveProbes.run(app.baseUrl()));
        } catch (Exception e) {
            report = LiveReport.notStarted("live check skipped: " + e.getMessage());
        }
        return NodeResult.ok(Map.of(StageGraph.LIVE_REPORT, Json.write(report)));
    }
}
