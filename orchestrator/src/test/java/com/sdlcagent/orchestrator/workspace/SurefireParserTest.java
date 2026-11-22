package com.sdlcagent.orchestrator.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.engine.FailureInfo;
import com.sdlcagent.orchestrator.engine.FailureKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireParserTest {
    private static final String PKG = "com.sdlcagent.urlshortener";
    @TempDir Path ws;

    private void report(String name, String body) throws IOException {
        Path d = ws.resolve("target/surefire-reports");
        Files.createDirectories(d);
        Files.writeString(d.resolve("TEST-" + name + ".xml"),
                "<?xml version=\"1.0\"?><testsuite name=\"" + name + "\">" + body + "</testsuite>");
    }

    private static MavenRunner.Result run(int exit, String out) {
        return new MavenRunner.Result(exit, false, out, 10);
    }

    private static String owner(String key) {
        return key.contains("Link") ? "impl:T2" : null;
    }

    @Test
    void parsesPassFailErrorSkip() throws IOException {
        report("LinkTest",
                "<testcase classname=\"c.LinkTest\" name=\"ok\"/>"
                        + "<testcase classname=\"c.LinkTest\" name=\"bad\"><failure message=\"expected 1\" type=\"org.opentest4j.AssertionFailedError\">trace</failure></testcase>"
                        + "<testcase classname=\"c.LinkTest\" name=\"boom\"><error message=\"npe\" type=\"java.lang.NullPointerException\">at x</error></testcase>"
                        + "<testcase classname=\"c.LinkTest\" name=\"later\"><skipped/></testcase>");
        SurefireParser.TestSummary s = SurefireParser.parseReports(ws);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.failures()).isEqualTo(1);
        assertThat(s.errors()).isEqualTo(1);
        assertThat(s.skipped()).isEqualTo(1);
        assertThat(s.allPassed()).isFalse();
        assertThat(s.failing()).extracting(SurefireParser.TestCase::id).containsExactly("c.LinkTest#bad", "c.LinkTest#boom");
    }

    @Test
    void noReportsMeansNotAllPassed() {
        SurefireParser.TestSummary s = SurefireParser.parseReports(ws);
        assertThat(s.total()).isZero();
        assertThat(s.allPassed()).isFalse();
    }

    @Test
    void classifiesCompileErrorsByMainVersusTest() {
        String main = "[ERROR] /w/src/main/java/com/sdlcagent/urlshortener/LinkService.java:[12,5] cannot find symbol\n";
        FailureInfo f = SurefireParser.classify(run(1, main), SurefireParser.parseReports(ws), PKG, SurefireParserTest::owner);
        assertThat(f.kind()).isEqualTo(FailureKind.COMPILE_MAIN);
        assertThat(f.ownerNodeId()).isEqualTo("impl:T2");
        assertThat(f.signature()).endsWith("LinkService.java:12");

        String test = "[ERROR] C:\\w\\src\\test\\java\\com\\example\\shortener\\FooTest.java:[3,1] package x does not exist\n";
        assertThat(SurefireParser.classify(run(1, test), SurefireParser.parseReports(ws), PKG, SurefireParserTest::owner).kind())
                .isEqualTo(FailureKind.COMPILE_TEST);
    }

    @Test
    void classifiesAssertionAndMainException() throws IOException {
        report("LinkTest",
                "<testcase classname=\"com.sdlcagent.urlshortener.LinkTest\" name=\"a\"><failure message=\"m\" type=\"org.opentest4j.AssertionFailedError\">x</failure></testcase>");
        FailureInfo a = SurefireParser.classify(run(1, ""), SurefireParser.parseReports(ws), PKG, SurefireParserTest::owner);
        assertThat(a.kind()).isEqualTo(FailureKind.ASSERTION);
        assertThat(a.signature()).isEqualTo("com.sdlcagent.urlshortener.LinkTest#a");

        Files.delete(ws.resolve("target/surefire-reports/TEST-LinkTest.xml"));
        String stack = "java.lang.NullPointerException\n\tat java.base/java.util.Objects.requireNonNull(Objects.java:1)\n"
                + "\tat com.sdlcagent.urlshortener.LinkService.shorten(LinkService.java:20)\n"
                + "\tat com.sdlcagent.urlshortener.LinkServiceTest.t(LinkServiceTest.java:9)\n";
        report("LinkServiceTest",
                "<testcase classname=\"com.sdlcagent.urlshortener.LinkServiceTest\" name=\"t\"><error message=\"npe\" type=\"java.lang.NullPointerException\">"
                        + stack + "</error></testcase>");
        FailureInfo e = SurefireParser.classify(run(1, ""), SurefireParser.parseReports(ws), PKG, SurefireParserTest::owner);
        assertThat(e.kind()).isEqualTo(FailureKind.EXCEPTION_MAIN);
        assertThat(e.ownerNodeId()).isEqualTo("impl:T2");
    }

    @Test
    void exceptionThrownInTestCodeIsNotBlamedOnMain() throws IOException {
        String stack = "java.lang.IllegalStateException\n\tat com.sdlcagent.urlshortener.LinkServiceTest.t(LinkServiceTest.java:9)\n";
        report("LinkServiceTest",
                "<testcase classname=\"com.sdlcagent.urlshortener.LinkServiceTest\" name=\"t\"><error message=\"x\" type=\"java.lang.IllegalStateException\">"
                        + stack + "</error></testcase>");
        FailureInfo f = SurefireParser.classify(run(1, ""), SurefireParser.parseReports(ws), PKG, SurefireParserTest::owner);
        assertThat(f.kind()).isEqualTo(FailureKind.OTHER);
    }

    @Test
    void greenBuildTimeoutAndUnknownFailure() throws IOException {
        report("T", "<testcase classname=\"c.T\" name=\"ok\"/>");
        assertThat(SurefireParser.classify(run(0, ""), SurefireParser.parseReports(ws), PKG, k -> null)).isNull();
        assertThat(SurefireParser.classify(new MavenRunner.Result(-1, true, "", 5), SurefireParser.parseReports(ws), PKG, k -> null).kind())
                .isEqualTo(FailureKind.TRANSIENT);
        assertThat(SurefireParser.classify(run(1, "BUILD FAILURE"), SurefireParser.parseReports(ws), PKG, k -> null).kind())
                .isEqualTo(FailureKind.OTHER);
    }
}
