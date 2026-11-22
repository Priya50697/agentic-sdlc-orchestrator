package com.sdlcagent.orchestrator.workspace;

import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Turns Surefire XML and javac output into structured data, and classifies the first failure for the router. */
public final class SurefireParser {
    private SurefireParser() {}

    public enum Status { PASSED, FAILED, ERROR, SKIPPED }

    public record TestCase(String className, String name, Status status, String type, String message, String stack) {
        public String id() {
            return className + "#" + name;
        }
    }

    public record TestSummary(int total, int failures, int errors, int skipped, List<TestCase> cases) {
        public boolean allPassed() {
            return total > 0 && failures == 0 && errors == 0;
        }

        public List<TestCase> failing() {
            return cases.stream().filter(c -> c.status() == Status.FAILED || c.status() == Status.ERROR).toList();
        }
    }

    public record CompileError(String file, int line, String message) {
        public boolean inTests() {
            return file.replace('\\', '/').contains("/src/test/");
        }
    }

    private static final Pattern JAVAC = Pattern.compile("\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),\\d+\\]\\s+(.*)");

    /** Reads every TEST-*.xml under the workspace's target/surefire-reports. */
    public static TestSummary parseReports(Path workspace) {
        Path dir = workspace.resolve("target").resolve("surefire-reports");
        List<TestCase> cases = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList()) {
                    cases.addAll(parseFile(f));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return summarize(cases);
    }

    static TestSummary summarize(List<TestCase> cases) {
        int failures = 0, errors = 0, skipped = 0;
        for (TestCase c : cases) {
            switch (c.status()) {
                case FAILED -> failures++;
                case ERROR -> errors++;
                case SKIPPED -> skipped++;
                default -> { }
            }
        }
        return new TestSummary(cases.size(), failures, errors, skipped, List.copyOf(cases));
    }

    static List<TestCase> parseFile(Path xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(xml.toFile());
            NodeList nodes = doc.getElementsByTagName("testcase");
            List<TestCase> out = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                Element tc = (Element) nodes.item(i);
                String cls = tc.getAttribute("classname");
                String name = tc.getAttribute("name");
                Element bad = child(tc, "failure");
                Status status = Status.PASSED;
                if (bad != null) status = Status.FAILED;
                else if ((bad = child(tc, "error")) != null) status = Status.ERROR;
                else if (child(tc, "skipped") != null) status = Status.SKIPPED;
                out.add(new TestCase(cls, name, status,
                        bad == null ? null : bad.getAttribute("type"),
                        bad == null ? null : bad.getAttribute("message"),
                        bad == null ? null : bad.getTextContent()));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse " + xml + ": " + e.getMessage(), e);
        }
    }

    private static Element child(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : (Element) l.item(0);
    }

    public static List<CompileError> parseCompileErrors(String mavenOutput) {
        List<CompileError> out = new ArrayList<>();
        for (String line : mavenOutput.split("\\R")) {
            Matcher m = JAVAC.matcher(line);
            if (m.find()) out.add(new CompileError(m.group(1), Integer.parseInt(m.group(2)), m.group(3).trim()));
        }
        return out;
    }

    /**
     * First failure as the engine understands it, or null if the build was green.
     * Rules (same as the plan): compile error under src/test -> COMPILE_TEST, src/main -> COMPILE_MAIN;
     * assertion -> ASSERTION; other exception whose top project frame is not a test class -> EXCEPTION_MAIN.
     *
     * @param projectPackage package prefix that identifies "our" stack frames, e.g. com.sdlcagent.urlshortener
     * @param ownerOf        maps a source file path or class name to the owning node id (may return null)
     */
    public static FailureInfo classify(MavenRunner.Result run, TestSummary tests, String projectPackage,
                                       Function<String, String> ownerOf) {
        if (run.timedOut()) return FailureInfo.of(FailureKind.TRANSIENT, "maven timed out after " + run.durationMs() + " ms");
        List<CompileError> compile = parseCompileErrors(run.output());
        if (!compile.isEmpty()) {
            CompileError e = compile.get(0);
            FailureKind kind = e.inTests() ? FailureKind.COMPILE_TEST : FailureKind.COMPILE_MAIN;
            String msg = compile.stream().limit(10)
                    .map(c -> c.file() + ":" + c.line() + " " + c.message()).reduce((a, b) -> a + "\n" + b).orElse("");
            return new FailureInfo(kind, msg, e.file() + ":" + e.line(), ownerOf.apply(e.file()));
        }
        List<TestCase> failing = tests.failing();
        if (!failing.isEmpty()) {
            TestCase t = failing.get(0);
            boolean assertion = t.type() != null && (t.type().contains("AssertionError")
                    || t.type().contains("AssertionFailedError") || t.type().contains("ComparisonFailure"));
            String frame = topProjectFrame(t.stack(), projectPackage);
            String message = failing.stream().limit(10)
                    .map(c -> c.id() + ": " + c.type() + " " + c.message()).reduce((a, b) -> a + "\n" + b).orElse("");
            if (assertion) return new FailureInfo(FailureKind.ASSERTION, message, t.id(), ownerOf.apply(t.className()));
            boolean frameInTest = frame != null && frame.matches(".*(Test|Tests|IT)$");
            FailureKind kind = frame != null && !frameInTest ? FailureKind.EXCEPTION_MAIN : FailureKind.OTHER;
            return new FailureInfo(kind, message, t.id(), frame == null ? null : ownerOf.apply(frame));
        }
        if (run.exitCode() != 0) {
            return FailureInfo.of(FailureKind.OTHER, "maven exit " + run.exitCode() + "\n" + tail(run.output(), 3000));
        }
        return null;
    }

    /** Class name of the first stack frame inside the project package, or null. */
    static String topProjectFrame(String stack, String projectPackage) {
        if (stack == null) return null;
        Matcher m = Pattern.compile("at\\s+(" + Pattern.quote(projectPackage) + "[\\w.$]*)\\.[\\w$<>]+\\(").matcher(stack);
        return m.find() ? m.group(1) : null;
    }

    static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
