package com.sdlcagent.orchestrator.context;

import java.util.List;
import java.util.stream.Collectors;

/** Lightweight structural view of the workspace's Java sources: enough for impact analysis and doc checks. */
public record CodeIndex(List<ClassInfo> classes) {

    public record Endpoint(String verb, String path, String method) {}

    /** {@code parsed=false} means the regex fallback produced this entry (partial: no method bodies, maybe no endpoints). */
    public record ClassInfo(String file, String packageName, String name, String kind, List<String> annotations,
                            List<String> methods, List<Endpoint> endpoints, boolean parsed,
                            List<String> constructors) {
        public ClassInfo(String file, String packageName, String name, String kind, List<String> annotations,
                         List<String> methods, List<Endpoint> endpoints, boolean parsed) {
            this(file, packageName, name, kind, annotations, methods, endpoints, parsed, List.of());
        }

        public String qualifiedName() {
            return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
        }
    }

    public boolean hasFile(String path) {
        String p = path.replace('\\', '/');
        return classes.stream().anyMatch(c -> c.file().equals(p));
    }

    public boolean hasClass(String nameOrQualified) {
        return classes.stream().anyMatch(c -> c.name().equals(nameOrQualified) || c.qualifiedName().equals(nameOrQualified));
    }

    public List<Endpoint> endpoints() {
        return classes.stream().flatMap(c -> c.endpoints().stream()).toList();
    }

    /** Compact text for prompts, cut at {@code maxChars} (whole classes only). */
    public String summary(int maxChars) {
        StringBuilder sb = new StringBuilder();
        int omitted = 0;
        for (ClassInfo c : classes) {
            String block = render(c);
            if (sb.length() + block.length() > maxChars) {
                omitted++;
                continue;
            }
            sb.append(block);
        }
        if (omitted > 0) sb.append("... ").append(omitted).append(" more classes omitted (budget)\n");
        return sb.toString();
    }

    private static String render(ClassInfo c) {
        StringBuilder b = new StringBuilder();
        b.append(c.file()).append(": ");
        if (!c.annotations().isEmpty()) b.append(String.join(" ", c.annotations())).append(' ');
        b.append(c.kind()).append(' ').append(c.qualifiedName());
        if (!c.parsed()) b.append("  [regex fallback]");
        b.append('\n');
        for (String k : c.constructors()) b.append("  constructor ").append(k).append('\n');
        for (String m : c.methods()) b.append("  ").append(m).append('\n');
        for (Endpoint e : c.endpoints()) {
            b.append("  endpoint ").append(e.verb()).append(' ').append(e.path()).append(" -> ").append(e.method()).append('\n');
        }
        return b.toString();
    }

    public String describeEndpoints() {
        return endpoints().stream().map(e -> e.verb() + " " + e.path()).collect(Collectors.joining(", "));
    }
}
