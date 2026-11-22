package com.sdlcagent.orchestrator.agents.release;

import java.util.List;

/** The reviewer's LLM output: judgement and narrative only. Facts are attached by Java from the audit log. */
public record ReleaseReview(String verdict, String summary, List<String> risks, List<String> followUps) {
    public ReleaseReview {
        risks = risks == null ? List.of() : risks;
        followUps = followUps == null ? List.of() : followUps;
    }

    public boolean ready() {
        return "READY".equalsIgnoreCase(verdict);
    }
}
