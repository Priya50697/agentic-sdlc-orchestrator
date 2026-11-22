package com.sdlcagent.orchestrator.observability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Derives every reliability metric from the audit log, so the log stays the single source of truth. */
public class MetricsService {

    public record Summary(
            String runId, String outcome,
            long nodesPassed, long nodesFailed, double nodeSuccessRate,
            long retries, long routeBacks, long rollbacks, long invalidations,
            long policyViolations, long humanApprovals,
            long llmCalls, long inputTokens, long outputTokens, double costUsd,
            long failuresRecovered, Double mttrMillis, Long e2eLatencyMillis) {}

    public Summary compute(Path auditFile) {
        try {
            return compute(AuditLogger.readAll(auditFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Summary compute(List<AuditEvent> events) {
        String runId = events.isEmpty() ? null : events.get(0).runId();
        String outcome = "IN_PROGRESS";
        long passed = 0, failed = 0, retries = 0, routeBacks = 0, rollbacks = 0, invalidations = 0;
        long violations = 0, approvals = 0, llmCalls = 0, in = 0, out = 0;
        double cost = 0;
        Instant start = null, end = null;

        // MTTR: first failure of a node -> the next time that same node passes.
        Map<String, Instant> openFailures = new HashMap<>();
        long recovered = 0;
        Duration recoveryTotal = Duration.ZERO;

        for (AuditEvent e : events) {
            switch (e.type()) {
                case RUN_STARTED -> { if (start == null) start = e.ts(); }
                case RUN_COMPLETED -> { outcome = "COMPLETED"; end = e.ts(); }
                case RUN_SAFE_STOPPED -> { outcome = "SAFE_STOPPED"; end = e.ts(); }
                case RUN_ABORTED -> { outcome = "ABORTED"; end = e.ts(); }
                case NODE_PASSED -> {
                    passed++;
                    Instant failedAt = e.nodeId() == null ? null : openFailures.remove(e.nodeId());
                    if (failedAt != null) {
                        recovered++;
                        recoveryTotal = recoveryTotal.plus(Duration.between(failedAt, e.ts()));
                    }
                }
                case NODE_FAILED -> {
                    failed++;
                    if (e.nodeId() != null) openFailures.putIfAbsent(e.nodeId(), e.ts());
                }
                case RETRY -> retries++;
                case ROUTE_BACK -> routeBacks++;
                case ROLLBACK -> rollbacks++;
                case INVALIDATED -> invalidations++;
                case POLICY_VIOLATION -> violations++;
                case HUMAN_APPROVAL -> approvals++;
                case AGENT_END -> {
                    if (e.data().get("llmCalls") instanceof Number n) {
                        // engine-reported count: 0 for non-LLM nodes such as compile gates
                        if (!Boolean.TRUE.equals(e.data().get("cacheHit"))) llmCalls += n.longValue();
                    } else if (!Boolean.TRUE.equals(e.data().get("cacheHit"))) {
                        llmCalls++;
                    }
                    in += num(e.data().get("inputTokens")).longValue();
                    out += num(e.data().get("outputTokens")).longValue();
                    cost += num(e.data().get("costUsd")).doubleValue();
                }
                default -> { }
            }
        }
        long attempts = passed + failed;
        double rate = attempts == 0 ? 0.0 : (double) passed / attempts;
        Double mttr = recovered == 0 ? null : (double) recoveryTotal.toMillis() / recovered;
        Long e2e = (start != null && end != null) ? Duration.between(start, end).toMillis() : null;
        return new Summary(runId, outcome, passed, failed, rate, retries, routeBacks, rollbacks, invalidations,
                violations, approvals, llmCalls, in, out, cost, recovered, mttr, e2e);
    }

    private static Number num(Object o) {
        return o instanceof Number n ? n : 0;
    }

    public void writeSummary(Path auditFile, Path target) {
        try {
            Files.createDirectories(target.getParent());
            AuditLogger.MAPPER.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), compute(auditFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
