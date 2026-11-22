package com.sdlcagent.orchestrator.agents.release;

import com.sdlcagent.orchestrator.observability.AuditEvent;
import com.sdlcagent.orchestrator.observability.AuditEventType;
import com.sdlcagent.orchestrator.observability.AuditLogger;
import com.sdlcagent.orchestrator.observability.MetricsService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Facts read from the audit log (not from any LLM): the release gate and the reviewer's prompt both use them. */
public record ReleaseFacts(boolean auditChainValid, long auditEvents, long criticalViolations, long totalViolations,
                           List<String> missingStages, MetricsService.Summary metrics) {

    /** Stages that must have passed before release readiness is reviewed. */
    static final Set<String> REQUIRED_STAGES = Set.of("requirements", "architecture", "implementation", "testing", "docs");

    public static ReleaseFacts collect(Path auditFile, boolean greenfield) {
        List<AuditEvent> events;
        try {
            events = AuditLogger.readAll(auditFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AuditLogger.Verification v = AuditLogger.verify(auditFile);
        long critical = events.stream().filter(e -> e.type() == AuditEventType.POLICY_VIOLATION)
                .filter(e -> "CRITICAL".equals(String.valueOf(e.data().get("severity")))).count();
        long total = events.stream().filter(e -> e.type() == AuditEventType.POLICY_VIOLATION).count();
        Set<String> passed = new java.util.HashSet<>();
        events.stream().filter(e -> e.type() == AuditEventType.NODE_PASSED).forEach(e -> passed.add(e.nodeId()));
        List<String> missing = REQUIRED_STAGES.stream().filter(s -> !passed.contains(s)).sorted().toList();
        return new ReleaseFacts(v.valid(), v.events(), critical, total, missing, new MetricsService().compute(events));
    }
}
