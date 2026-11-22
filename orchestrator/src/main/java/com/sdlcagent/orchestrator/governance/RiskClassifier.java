package com.sdlcagent.orchestrator.governance;

import java.util.ArrayList;
import java.util.List;

/** Marks high-impact actions; any finding means the change set needs human approval before it is applied. */
public class RiskClassifier {
    public record RiskFinding(String path, String reason) {}

    public List<RiskFinding> classify(List<FileChange> changes) {
        List<RiskFinding> out = new ArrayList<>();
        for (FileChange c : changes) {
            String p = Policies.norm(c.path());
            if (p.endsWith("pom.xml")) out.add(new RiskFinding(c.path(), "build/dependency change"));
            if (c.op() == FileChange.Op.DELETE) out.add(new RiskFinding(c.path(), "file deletion"));
            if (c.op() == FileChange.Op.MODIFY && p.contains("src/test/")) {
                out.add(new RiskFinding(c.path(), "editing an existing test"));
            }
        }
        return out;
    }
}
