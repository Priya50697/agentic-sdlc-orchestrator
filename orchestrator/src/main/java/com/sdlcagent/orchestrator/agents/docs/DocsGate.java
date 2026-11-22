package com.sdlcagent.orchestrator.agents.docs;

import com.sdlcagent.orchestrator.agents.base.GateBean;
import com.sdlcagent.orchestrator.context.CodeIndexer;
import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import com.sdlcagent.orchestrator.governance.FileChange;
import com.sdlcagent.orchestrator.governance.GateContext;
import com.sdlcagent.orchestrator.governance.GateResult;
import com.sdlcagent.orchestrator.workspace.WorkspaceProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Required sections present, and the documented endpoints equal the actual @*Mapping endpoints in the code. */
@Component
public class DocsGate implements GateBean {
    private final CodeIndexer indexer;
    private final WorkspaceProperties workspace;

    public DocsGate(CodeIndexer indexer, WorkspaceProperties workspace) {
        this.indexer = indexer;
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "docs-valid";
    }

    @Override
    public GateResult evaluate(GateContext ctx) {
        if (ctx.result() == null) return GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, "no result to check"));
        String readme = null;
        StringBuilder all = new StringBuilder();
        for (FileChange c : ctx.result().changes()) {
            if (c.content() == null) continue;
            all.append('\n').append(c.content());
            if (c.path().equals("README.md")) readme = c.content();
        }
        List<String> errors = new ArrayList<>();
        if (readme == null) errors.add("README.md was not produced");
        else {
            List<String> missing = DocText.missingSections(readme);
            if (!missing.isEmpty()) errors.add("README.md lacks sections " + missing);
        }
        Set<String> documented = DocText.documentedEndpoints(all.toString());
        Set<String> actual = DocText.actualEndpoints(indexer.index(workspace.dir()));
        Set<String> undocumented = new LinkedHashSet<>(actual);
        undocumented.removeAll(documented);
        Set<String> invented = new LinkedHashSet<>(documented);
        invented.removeAll(actual);
        if (!undocumented.isEmpty()) errors.add("endpoints missing from the docs: " + undocumented);
        if (!invented.isEmpty()) errors.add("docs describe endpoints that do not exist: " + invented);
        return errors.isEmpty() ? GateResult.pass()
                : GateResult.fail(FailureInfo.of(FailureKind.TRANSIENT, String.join("; ", errors)));
    }
}
