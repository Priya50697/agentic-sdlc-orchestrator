package com.sdlcagent.orchestrator.agents.testing;

import java.util.List;

/** Measured facts from a real Maven run (not LLM claims); the TestsGate judges them. */
public record TestReport(String summary, int total, int failures, int errors, int skipped,
                         List<AcCoverage> coverage, List<String> uncoveredCriteria) {

    /** passingTests = display names / ids of passing tests that mention the criterion. */
    public record AcCoverage(String criterion, List<String> passingTests) {}

    public TestReport {
        coverage = coverage == null ? List.of() : coverage;
        uncoveredCriteria = uncoveredCriteria == null ? List.of() : uncoveredCriteria;
    }

    public boolean allPassed() {
        return total > 0 && failures == 0 && errors == 0;
    }
}
