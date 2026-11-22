package com.sdlcagent.orchestrator.api;

import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Channel;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Decision;
import com.sdlcagent.orchestrator.service.RunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resolving an approval also resumes the run (it went idle in WAITING_HUMAN). */
@RestController
@RequestMapping("/api/runs/{runId}/approvals")
public class ApprovalController {
    public record DecisionRequest(@NotNull Decision decision, String feedback, String actor) {}

    private final RunService runs;

    public ApprovalController(RunService runs) {
        this.runs = runs;
    }

    @GetMapping
    public List<ApprovalRequest> list(@PathVariable String runId) {
        return runs.approvals(runId);
    }

    @PostMapping("/{approvalId}")
    public ApprovalRequest decide(@PathVariable String runId, @PathVariable String approvalId,
                                  @Valid @RequestBody DecisionRequest req) {
        ApprovalRequest r = runs.resolve(runId, approvalId, req.decision(), req.feedback(), Channel.REST,
                req.actor() == null ? "rest-user" : req.actor());
        runs.runAsync(runId);
        return r;
    }
}
