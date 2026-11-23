package com.sdlcagent.orchestrator.agents.livecheck;

import java.util.List;

/**
 * Result of the advisory live endpoint check. {@code started=false} means the app could not be packaged or booted
 * (see {@code note}); the stage still passes because the check is advisory.
 */
public record LiveReport(boolean started, String note, List<Check> checks) {
    /** severity: "required" probes cover the spec'd behaviour; "hardening" probes are best-practice extras. */
    public record Check(String name, String expected, String actual, boolean passed, String severity) {}

    public long passedCount() {
        return checks.stream().filter(Check::passed).count();
    }

    public long failedCount() {
        return checks.size() - passedCount();
    }

    public static LiveReport notStarted(String note) {
        return new LiveReport(false, note, List.of());
    }
}
