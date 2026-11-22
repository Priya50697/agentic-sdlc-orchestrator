package com.sdlcagent.orchestrator.context;

import com.sdlcagent.orchestrator.context.CodeIndex.ClassInfo;
import com.sdlcagent.orchestrator.context.CodeIndex.Endpoint;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deliberately shallow: {@code Files.walk} + StaticJavaParser signature extraction (no symbol resolution),
 * with a regex fallback for files that fail to parse. Only src/main and src/test .java files are indexed.
 */
public class CodeIndexer {
    private static final Map<String, String> MAPPINGS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH", "RequestMapping", "ANY");
    private static final Pattern FALLBACK_TYPE = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|static\\s+)*(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern FALLBACK_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern FALLBACK_ANNOTATION = Pattern.compile("(?m)^\\s*(@\\w+)");

    static {
        ParserConfiguration cfg = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(cfg);
    }

    public CodeIndex index(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        List<ClassInfo> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String rel = rel(root, p);
                        return rel.startsWith("src/main/") || rel.startsWith("src/test/");
                    })
                    .sorted(Comparator.comparing(p -> rel(root, p)))
                    .toList();
            for (Path f : javaFiles) out.addAll(indexFile(rel(root, f), read(f)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CodeIndex(List.copyOf(out));
    }

    List<ClassInfo> indexFile(String relPath, String source) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(source);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            List<ClassInfo> result = new ArrayList<>();
            for (TypeDeclaration<?> t : cu.getTypes()) result.add(fromType(relPath, pkg, t));
            if (result.isEmpty()) return fallback(relPath, source);
            return result;
        } catch (RuntimeException e) {
            return fallback(relPath, source);
        }
    }

    private ClassInfo fromType(String file, String pkg, TypeDeclaration<?> t) {
        List<String> annotations = t.getAnnotations().stream().map(CodeIndexer::annotationText).toList();
        String base = t.getAnnotations().stream()
                .filter(a -> a.getNameAsString().equals("RequestMapping"))
                .map(CodeIndexer::pathOf).findFirst().orElse("");
        List<String> methods = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        for (MethodDeclaration m : t.getMethods()) {
            if (m.hasModifier(Modifier.Keyword.PRIVATE)) continue;
            methods.add(m.getDeclarationAsString(true, false, true));
            for (AnnotationExpr a : m.getAnnotations()) {
                String verb = MAPPINGS.get(a.getNameAsString());
                if (verb == null) continue;
                if (a.getNameAsString().equals("RequestMapping")) verb = requestMethod(a);
                endpoints.add(new Endpoint(verb, join(base, pathOf(a)), m.getNameAsString()));
            }
        }
        return new ClassInfo(file, pkg, t.getNameAsString(), kind(t), annotations, methods, endpoints, true);
    }

    private static String kind(TypeDeclaration<?> t) {
        if (t.isEnumDeclaration()) return "enum";
        if (t.isRecordDeclaration()) return "record";
        if (t.isClassOrInterfaceDeclaration()) return t.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
        return "type";
    }

    private static String annotationText(AnnotationExpr a) {
        return "@" + a.getNameAsString();
    }

    private static String pathOf(AnnotationExpr a) {
        if (a instanceof SingleMemberAnnotationExpr s) return firstString(s.getMemberValue());
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals("value") || p.getNameAsString().equals("path")) {
                    return firstString(p.getValue());
                }
            }
        }
        return "";
    }

    private static String requestMethod(AnnotationExpr a) {
        if (a instanceof NormalAnnotationExpr n) {
            for (MemberValuePair p : n.getPairs()) {
                if (p.getNameAsString().equals("method")) {
                    return p.getValue().toString().replaceAll(".*\\.", "").replaceAll("[{}\\s]", "");
                }
            }
        }
        return "ANY";
    }

    private static String firstString(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.asString();
        if (e instanceof ArrayInitializerExpr arr && !arr.getValues().isEmpty()) return firstString(arr.getValues().get(0));
        return "";
    }

    private static String join(String base, String sub) {
        String b = base == null ? "" : base;
        String s = sub == null ? "" : sub;
        String joined = (b.isEmpty() || b.startsWith("/") ? b : "/" + b) + (s.isEmpty() || s.startsWith("/") ? s : "/" + s);
        return joined.isEmpty() ? "/" : joined;
    }

    private List<ClassInfo> fallback(String file, String source) {
        Matcher pm = FALLBACK_PACKAGE.matcher(source);
        String pkg = pm.find() ? pm.group(1) : "";
        List<String> annotations = new ArrayList<>();
        Matcher am = FALLBACK_ANNOTATION.matcher(source);
        while (am.find() && annotations.size() < 6) annotations.add(am.group(1));
        List<ClassInfo> out = new ArrayList<>();
        Matcher tm = FALLBACK_TYPE.matcher(source);
        while (tm.find()) {
            out.add(new ClassInfo(file, pkg, tm.group(2), tm.group(1), annotations, List.of(), List.of(), false));
        }
        if (out.isEmpty()) {
            String name = file.substring(file.lastIndexOf('/') + 1).replace(".java", "");
            out.add(new ClassInfo(file, pkg, name, "type", annotations, List.of(), List.of(), false));
        }
        return out;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
