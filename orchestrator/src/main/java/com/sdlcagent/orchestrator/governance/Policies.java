package com.sdlcagent.orchestrator.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Built-in policies: security (secrets, forbidden APIs), compliance (pom allowlist), change control. */
public final class Policies {
    private Policies() {}

    static String norm(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    static boolean escapesSandbox(String path) {
        String p = norm(path);
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) return true;
        for (String seg : p.split("/")) if (seg.equals("..")) return true;
        return false;
    }

    public static class Secrets implements Policy {
        private record Rule(Pattern pattern, Severity severity, String what) {}

        private static final List<Rule> RULES = List.of(
                new Rule(Pattern.compile("AKIA[0-9A-Z]{16}"), Severity.CRITICAL, "AWS access key"),
                new Rule(Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"), Severity.CRITICAL, "private key"),
                new Rule(Pattern.compile("(?i)(api[_-]?key|secret|password|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_\\-]{12,}"),
                        Severity.HIGH, "hard-coded credential"));

        @Override public String id() { return "security.secrets"; }

        @Override
        public List<Violation> evaluate(PolicyInput in) {
            List<Violation> out = new ArrayList<>();
            for (FileChange c : in.changes()) {
                if (c.content() != null) scan(c.content(), c.path(), out);
            }
            in.artifacts().forEach((id, text) -> scan(text, "artifact:" + id, out));
            return out;
        }

        private void scan(String text, String where, List<Violation> out) {
            for (Rule r : RULES) {
                if (r.pattern().matcher(text).find()) {
                    out.add(new Violation(id(), r.severity(), "possible " + r.what() + " in " + where, where));
                }
            }
        }
    }

    public static class ForbiddenApis implements Policy {
        private static final List<Pattern> BAD = List.of(
                Pattern.compile("Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec"),
                Pattern.compile("new\\s+ProcessBuilder"),
                Pattern.compile("System\\s*\\.\\s*exit\\s*\\("));

        @Override public String id() { return "security.forbidden-apis"; }

        @Override
        public List<Violation> evaluate(PolicyInput in) {
            List<Violation> out = new ArrayList<>();
            for (FileChange c : in.changes()) {
                if (c.content() == null || !norm(c.path()).endsWith(".java")) continue;
                for (Pattern p : BAD) {
                    if (p.matcher(c.content()).find()) {
                        out.add(new Violation(id(), Severity.HIGH, "forbidden API (" + p.pattern() + ") in " + c.path(), c.path()));
                    }
                }
            }
            return out;
        }
    }

    public static class PomAllowlist implements Policy {
        private static final Pattern DEP = Pattern.compile(
                "<dependency>.*?<artifactId>\\s*([^<\\s]+)\\s*</artifactId>.*?</dependency>", Pattern.DOTALL);
        private final Set<String> exact;
        private final List<String> prefixes;

        public PomAllowlist(Set<String> exact, List<String> prefixes) {
            this.exact = exact;
            this.prefixes = prefixes;
        }

        public static PomAllowlist defaults() {
            return new PomAllowlist(Set.of("h2", "lombok", "jakarta.validation-api", "resilience4j-circuitbreaker"),
                    List.of("spring-boot-starter", "spring-boot-test", "spring-boot-devtools"));
        }

        @Override public String id() { return "compliance.pom-allowlist"; }

        @Override
        public List<Violation> evaluate(PolicyInput in) {
            List<Violation> out = new ArrayList<>();
            for (FileChange c : in.changes()) {
                if (c.content() == null || !norm(c.path()).endsWith("pom.xml")) continue;
                Matcher m = DEP.matcher(c.content());
                while (m.find()) {
                    String artifact = m.group(1);
                    boolean ok = exact.contains(artifact) || prefixes.stream().anyMatch(artifact::startsWith);
                    if (!ok) {
                        out.add(new Violation(id(), Severity.HIGH,
                                "dependency '" + artifact + "' is not on the allowlist", c.path()));
                    }
                }
            }
            return out;
        }
    }

    public static class ChangeControl implements Policy {
        private final int maxFiles;
        private final List<String> protectedPrefixes;

        public ChangeControl(int maxFiles, List<String> protectedPrefixes) {
            this.maxFiles = maxFiles;
            this.protectedPrefixes = protectedPrefixes;
        }

        public static ChangeControl defaults() {
            return new ChangeControl(12, List.of(".orchestrator/", ".git/", "mvnw", "mvnw.cmd", ".mvn/"));
        }

        @Override public String id() { return "change-control"; }

        @Override
        public List<Violation> evaluate(PolicyInput in) {
            List<Violation> out = new ArrayList<>();
            if (in.changes().size() > maxFiles) {
                out.add(new Violation(id(), Severity.HIGH,
                        in.changes().size() + " files changed, limit is " + maxFiles, null));
            }
            for (FileChange c : in.changes()) {
                String p = norm(c.path());
                if (escapesSandbox(p)) {
                    out.add(new Violation(id(), Severity.CRITICAL, "path escapes the sandbox: " + c.path(), c.path()));
                    continue;
                }
                if (protectedPrefixes.stream().anyMatch(p::startsWith)) {
                    out.add(new Violation(id(), Severity.HIGH, "protected path: " + c.path(), c.path()));
                }
                if (c.op() == FileChange.Op.DELETE && p.contains("src/test/")) {
                    out.add(new Violation(id(), Severity.HIGH, "deleting tests is not allowed: " + c.path(), c.path()));
                }
            }
            return out;
        }
    }
}
