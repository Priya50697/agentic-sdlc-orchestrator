package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.engine.TaskGraph;
import com.sdlcagent.orchestrator.workspace.TestInventory;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a failing source file or class name to the implementation node that owns it ("impl:T2"), using the
 * target paths in the task graph. Returns null when nothing matches (the engine then retries the failing node).
 */
public final class OwnerResolver {
    private OwnerResolver() {}

    public static Function<String, String> from(String taskGraphJson) {
        return from(taskGraphJson, Set.of());
    }

    /**
     * @param baselineTestIds ids ("Class#method") of tests that existed before the run. Their acN numbers come from
     *                        the old spec, so they are never mapped to a task by acceptance criterion.
     */
    public static Function<String, String> from(String taskGraphJson, Set<String> baselineTestIds) {
        TaskGraph graph;
        try {
            graph = TaskGraph.parse(taskGraphJson);
        } catch (RuntimeException e) {
            return x -> null;
        }
        return what -> owner(graph, what, baselineTestIds);
    }

    static String owner(TaskGraph graph, String fileOrClass) {
        return owner(graph, fileOrClass, Set.of());
    }

    static String owner(TaskGraph graph, String fileOrClass, Set<String> baselineTestIds) {
        if (fileOrClass == null || fileOrClass.isBlank()) return null;
        boolean baseline = fileOrClass.contains("#") && baselineTestIds.contains(TestInventory.normalizeId(fileOrClass));
        String byAc = baseline ? null : ownerByCriterion(graph, fileOrClass);
        if (byAc != null) return byAc;
        String f = fileOrClass.replace('\\', '/');
        String stem = stem(f);
        for (TaskGraph.TaskSpec t : graph.tasks()) {
            for (String p : t.targetPaths()) {
                String np = p.replace('\\', '/');
                if (f.endsWith(np) || (!stem.isBlank() && stem(np).equals(stem))) return "impl:" + t.id();
            }
        }
        // a test class such as ShortenerControllerTest points at the class it tests
        String tested = stem.replaceAll("(Tests|Test|IT)$", "");
        if (!tested.equals(stem) && !tested.isBlank()) {
            for (TaskGraph.TaskSpec t : graph.tasks()) {
                for (String p : t.targetPaths()) {
                    if (stem(p.replace('\\', '/')).equals(tested)) return "impl:" + t.id();
                }
            }
        }
        return null;
    }

    private static final Pattern TEST_AC = Pattern.compile("#ac_?(\\d+)(?!\\d)", Pattern.CASE_INSENSITIVE);

    /** A test id such as "FooTest#ac6_x" belongs to the task whose acceptanceCriteria include AC-6. */
    static String ownerByCriterion(TaskGraph graph, String testId) {
        Matcher m = TEST_AC.matcher(testId);
        if (!m.find()) return null;
        String num = m.group(1);
        for (TaskGraph.TaskSpec t : graph.tasks()) {
            for (String ac : t.acceptanceCriteria()) {
                if (ac != null && ac.replaceFirst("(?i)^AC[-_]?", "").strip().equals(num)) return "impl:" + t.id();
            }
        }
        return null;
    }

    /** "a/b/Foo.java" or "com.x.Foo" or "com.x.Foo$Inner" -> "Foo". */
    static String stem(String s) {
        String name = s.substring(s.lastIndexOf('/') + 1);
        int dot = name.indexOf(".java");
        if (dot >= 0) name = name.substring(0, dot);
        else name = name.substring(name.lastIndexOf('.') + 1);
        int dollar = name.indexOf('$');
        return dollar >= 0 ? name.substring(0, dollar) : name;
    }
}
