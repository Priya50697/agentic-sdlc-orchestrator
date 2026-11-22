package com.sdlcagent.orchestrator.dryrun;

import com.sdlcagent.orchestrator.workspace.MavenRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No Maven process. compile() succeeds except once (the second call, i.e. wave 1) when it reports a javac error in
 * the T1 entity, which must route back to impl:T1. test() "runs" the test sources by turning every
 * {@code @DisplayName("...")} into a passing Surefire testcase, so AC coverage comes from the real fixture files.
 */
class FakeMaven extends MavenRunner {
    private static final Pattern DISPLAY = Pattern.compile("@DisplayName\\(\"([^\"]+)\"\\)");

    private final Path ws;
    private final AtomicInteger compileCalls;

    FakeMaven(Path ws, AtomicInteger compileCalls) {
        super(ws, Duration.ofSeconds(1));
        this.ws = ws.toAbsolutePath().normalize();
        this.compileCalls = compileCalls;
    }

    @Override
    public MavenRunner withWorkspace(Path dir) {
        return new FakeMaven(dir, compileCalls);
    }

    @Override
    public Result compile() {
        if (compileCalls.incrementAndGet() == 2) {
            String file = ws.resolve("src/main/java/com/sdlcagent/urlshortener/model/ShortUrl.java").toString().replace('\\', '/');
            return new Result(1, false, "[ERROR] " + file + ":[12,5] cannot find symbol: class Instantq", 5);
        }
        return new Result(0, false, "BUILD SUCCESS", 5);
    }

    @Override
    public Result test() {
        Path dir = ws.resolve("target/surefire-reports");
        StringBuilder xml = new StringBuilder("<testsuite>\n");
        try {
            Files.createDirectories(dir);
            Path testRoot = ws.resolve("src/test/java");
            if (Files.isDirectory(testRoot)) {
                try (Stream<Path> files = Files.walk(testRoot)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        String cls = testRoot.relativize(f).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.java$", "");
                        Matcher m = DISPLAY.matcher(Files.readString(f));
                        while (m.find()) xml.append("<testcase classname=\"").append(cls).append("\" name=\"").append(m.group(1)).append("\"/>\n");
                    }
                }
            }
            Files.writeString(dir.resolve("TEST-fake.xml"), xml.append("</testsuite>").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Result(0, false, "BUILD SUCCESS", 5);
    }

}
