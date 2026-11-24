package com.sdlcagent.orchestrator.agents.release;

import java.util.List;

/** Stored as the release-report artifact (JSON) plus a markdown rendering for humans (Gate 2 reviewer). */
public record ReleaseReadinessReport(String verdict, String summary, List<String> risks, List<String> followUps,
                                     ReleaseFacts facts) {
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# Release readiness: ").append(verdict).append("\n\n").append(summary).append("\n\n");
        sb.append("## Facts (from the audit log, snapshot taken before Gate 2; run totals are in metrics-summary.json)\n");
        sb.append("- audit chain valid: ").append(facts.auditChainValid()).append(" (").append(facts.auditEvents()).append(" events)\n");
        sb.append("- policy violations: ").append(facts.totalViolations()).append(" (critical ").append(facts.criticalViolations()).append(")\n");
        var m = facts.metrics();
        sb.append("- node success rate: ").append(m.nodeSuccessRate()).append(", retries ").append(m.retries())
                .append(", route-backs ").append(m.routeBacks()).append(", rollbacks ").append(m.rollbacks()).append('\n');
        sb.append("- LLM calls ").append(m.llmCalls()).append(", tokens ").append(m.inputTokens() + m.outputTokens())
                .append(", cost $").append(m.costUsd()).append(", cache hits ").append(m.cacheHits()).append('\n');
        if (!risks.isEmpty()) sb.append("\n## Risks\n").append(risks.stream().map(r -> "- " + r + "\n").reduce("", String::concat));
        if (!followUps.isEmpty()) sb.append("\n## Follow-ups\n").append(followUps.stream().map(r -> "- " + r + "\n").reduce("", String::concat));
        return sb.toString();
    }
}
