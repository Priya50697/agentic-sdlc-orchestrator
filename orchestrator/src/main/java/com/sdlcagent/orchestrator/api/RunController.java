package com.sdlcagent.orchestrator.api;

import com.sdlcagent.orchestrator.observability.AuditEvent;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import com.sdlcagent.orchestrator.observability.MetricsService;
import com.sdlcagent.orchestrator.service.RunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class RunController {
    public record StartRequest(String scenario, @NotBlank String requirement, Boolean greenfield) {}

    public record AbortRequest(String reason) {}

    public record RequirementUpdate(@NotBlank String requirement) {}

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> start(@Valid @RequestBody StartRequest req) {
        boolean greenfield = req.greenfield() == null || req.greenfield();
        String id = runs.create(req.scenario(), req.requirement(), greenfield);
        runs.runAsync(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", id));
    }

    @GetMapping
    public List<RunService.Status> list() {
        return runs.list();
    }

    @GetMapping("/{id}")
    public RunService.Status status(@PathVariable String id) {
        return runs.status(id);
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable String id) {
        runs.runAsync(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/abort")
    public ResponseEntity<Void> abort(@PathVariable String id, @RequestBody(required = false) AbortRequest req) {
        runs.abort(id, req == null ? null : req.reason());
        return ResponseEntity.accepted().build();
    }

    @PutMapping("/{id}/requirement")
    public ResponseEntity<Void> updateRequirement(@PathVariable String id, @Valid @RequestBody RequirementUpdate req) {
        runs.updateRequirement(id, req.requirement());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/metrics")
    public MetricsService.Summary metrics(@PathVariable String id) {
        return runs.metrics(id);
    }

    @GetMapping("/{id}/audit")
    public List<AuditEvent> audit(@PathVariable String id) {
        return runs.audit(id);
    }

    @GetMapping("/{id}/audit/verify")
    public AuditLogger.Verification verify(@PathVariable String id) {
        return runs.verifyAudit(id);
    }

    @GetMapping("/{id}/artifacts/{artifactId}")
    public ResponseEntity<String> artifact(@PathVariable String id, @PathVariable String artifactId) {
        return runs.artifact(id, artifactId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
