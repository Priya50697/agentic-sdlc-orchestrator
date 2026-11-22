package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.engine.TaskGraph;
import java.util.function.Function;

/**
 * Maps a failing source file or class name to the implementation node that owns it ("impl:T2"), using the
 * target paths in the task graph. Returns null when nothing matches (the engine then retries the failing node).
 */
public final class OwnerResolver {
    private OwnerResolver() {}

    public static Function<String, String> from(String taskGraphJson) {
        TaskGraph graph;
        try {
            graph = TaskGraph.parse(taskGraphJson);
        } catch (RuntimeException e) {
            return x -> null;
        }
        return what -> owner(graph, what);
    }

    static String owner(TaskGraph graph, String fileOrClass) {
        if (fileOrClass == null || fileOrClass.isBlank()) return null;
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
