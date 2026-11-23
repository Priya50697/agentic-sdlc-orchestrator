package com.sdlcagent.orchestrator.governance;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one place that says what each agent may produce. LLM calls have no tools at all; the only capabilities an
 * agent has are the file changes and artifacts it returns, and this table bounds both. Enforced by
 * {@link Policies.AgentScope} (HIGH severity: the attempt fails and is retried with the violation as feedback).
 * An agent missing from the table may do nothing.
 */
public final class AgentPermissions {
    /** writeGlobs: workspace-relative paths the agent may create/modify ("**" crosses directories, "*" does not). */
    public record Permission(List<String> writeGlobs, boolean mayDelete, Set<String> artifacts) {
        public boolean allowsWrite(String path) {
            String p = Policies.norm(path);
            return writeGlobs.stream().anyMatch(g -> globToRegex(g).matcher(p).matches());
        }
    }

    private static final Permission NONE = new Permission(List.of(), false, Set.of());

    private final Map<String, Permission> table;

    public AgentPermissions(Map<String, Permission> table) {
        this.table = Map.copyOf(table);
    }

    public Permission of(String agent) {
        return table.getOrDefault(agent, NONE);
    }

    public boolean knows(String agent) {
        return table.containsKey(agent);
    }

    public static AgentPermissions defaults() {
        return new AgentPermissions(Map.of(
                "requirements", artifactsOnly("spec"),
                "codebase-analyst", artifactsOnly("impact"),
                "architect", artifactsOnly("architecture-plan", "task-graph"),
                "implementer", new Permission(List.of("src/main/**", "pom.xml"), true, Set.of()),
                "compile-gate", artifactsOnly(),
                "live-check", artifactsOnly("live-report"),
                "test-engineer", new Permission(List.of("src/test/**"), false, Set.of("test-report")),
                "doc-writer", new Permission(List.of("README.md", "docs/**/*.md", "docs/*.md"), false, Set.of("doc-report")),
                "release-reviewer", artifactsOnly("release-report", "release-report.md")));
    }

    private static Permission artifactsOnly(String... ids) {
        return new Permission(List.of(), false, Set.of(ids));
    }

    static Pattern globToRegex(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                } else {
                    re.append("[^/]*");
                }
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
