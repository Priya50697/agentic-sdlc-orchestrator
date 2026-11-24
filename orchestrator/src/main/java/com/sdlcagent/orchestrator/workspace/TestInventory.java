package com.sdlcagent.orchestrator.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads JUnit test methods out of Java sources without compiling them: id ({@code Class#method}) -> normalized body.
 * Used to snapshot the baseline tests of a brownfield run and to prove that a proposed test change neither removes
 * nor silently weakens any of them. Deterministic text analysis, no LLM.
 */
public final class TestInventory {
    private TestInventory() {}

    public static final String BASELINE_DIR = "baseline-tests";

    private static final Pattern TEST_ANNOTATION =
            Pattern.compile("@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    private static final Pattern METHOD =
            Pattern.compile("(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    /** path (any separator) -> file content, as {@code relative path -> source}. */
    public static Map<String, String> methods(Map<String, String> sources) {
        Map<String, String> out = new LinkedHashMap<>();
        sources.forEach((path, src) -> {
            if (path.replace('\\', '/').endsWith(".java") && src != null) out.putAll(methodsOf(stem(path), src));
        });
        return out;
    }

    /** All test methods under {@code <workspace>/src/test}. */
    public static Map<String, String> methods(Path workspace) {
        return methods(readTestSources(workspace));
    }

    /** Test source files under src/test, keyed by workspace-relative path with forward slashes. */
    public static Map<String, String> readTestSources(Path workspace) {
        Map<String, String> out = new LinkedHashMap<>();
        Path root = workspace.toAbsolutePath().normalize();
        Path tests = root.resolve("src").resolve("test");
        if (!Files.isDirectory(tests)) return out;
        try (Stream<Path> walk = Files.walk(tests)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.put(rel, Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** Copies src/test of the workspace into {@code dest/src/test}. No-op when there is no test directory. */
    public static void snapshot(Path workspace, Path dest) {
        Path root = workspace.toAbsolutePath().normalize();
        Path tests = root.resolve("src").resolve("test");
        if (!Files.isDirectory(tests)) return;
        try (Stream<Path> walk = Files.walk(tests)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                Path target = dest.resolve(root.relativize(p).toString());
                Files.createDirectories(target.getParent());
                Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "Class#method" from a Surefire id such as "com.x.FooTest#bar(String)[2]" or "FooTest#bar". */
    public static String normalizeId(String id) {
        if (id == null) return "";
        String s = id.strip();
        int hash = s.indexOf('#');
        if (hash < 0) return s;
        String cls = s.substring(0, hash);
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        String method = s.substring(hash + 1);
        int cut = indexOfAny(method, '(', '[');
        if (cut >= 0) method = method.substring(0, cut);
        return cls + "#" + method.strip();
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        if (i < 0) return j;
        return j < 0 ? i : Math.min(i, j);
    }

    private static String stem(String path) {
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    private static Map<String, String> methodsOf(String cls, String src) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher a = TEST_ANNOTATION.matcher(src);
        int from = 0;
        while (a.find(from)) {
            Matcher m = METHOD.matcher(src);
            if (!m.find(a.end())) break;
            int open = m.end() - 1;
            int close = matchingBrace(src, open);
            if (close < 0) break;
            out.put(cls + "#" + m.group(1), normalize(src.substring(open, close + 1)));
            from = close + 1;
        }
        return out;
    }

    /** Index of the brace closing the one at {@code open}, skipping strings, text blocks, chars and comments. */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                if (end < 0) return -1;
                i = end + 2;
            } else if (c == '"' || c == '\'') {
                i = skipQuoted(s, i, c);
            } else if (s.startsWith("//", i)) {
                int nl = s.indexOf('\n', i);
                if (nl < 0) return -1;
                i = nl;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) return -1;
                i = end + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipQuoted(String s, int start, char quote) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == quote) return i;
        }
        return s.length();
    }

    /** Whitespace-insensitive form so reformatting alone does not count as a change. */
    private static String normalize(String body) {
        return body.replaceAll("\\s+", "");
    }
}
