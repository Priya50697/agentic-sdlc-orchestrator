package com.sdlcagent.orchestrator.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.livecheck.LiveCheckExecutor;
import com.sdlcagent.orchestrator.agents.livecheck.LiveProbes;
import com.sdlcagent.orchestrator.agents.livecheck.LiveReport;
import com.sdlcagent.orchestrator.engine.NodeResult;
import com.sdlcagent.orchestrator.engine.StageGraph;
import com.sdlcagent.orchestrator.agents.livecheck.LiveAppRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Probes run against an in-JVM stub server; no Maven, no generated app. */
class LiveCheckTest {

    /** A stub that honours the whole contract; {@code acceptPrivate} controls the hardening probe. */
    private static HttpServer stub(boolean acceptPrivate) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (ex.getRequestMethod().equals("POST") && path.equals("/shorten")) {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean ok = body.contains("\"url\":\"https://") || (acceptPrivate && body.contains("169.254"));
                if (ok) reply(ex, 201, "{\"code\":\"abc1234\"}", null);
                else reply(ex, 400, "{\"status\":400}", null);
            } else if (path.equals("/abc1234")) {
                reply(ex, 302, "", "https://example.com/live-check?x=1");
            } else {
                reply(ex, 404, "{\"status\":404}", null);
            }
        });
        s.start();
        return s;
    }

    private static void reply(HttpExchange ex, int status, String body, String location) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (location != null) ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    private static String url(HttpServer s) {
        return "http://localhost:" + s.getAddress().getPort();
    }

    @Test
    void conformingAppPassesEveryProbe() throws Exception {
        HttpServer s = stub(false);
        try {
            List<LiveReport.Check> checks = LiveProbes.run(url(s));
            assertThat(checks).hasSize(9);
            assertThat(checks).allMatch(LiveReport.Check::passed);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void acceptingPrivateHostFailsOnlyTheHardeningProbe() throws Exception {
        HttpServer s = stub(true);
        try {
            List<LiveReport.Check> failed = LiveProbes.run(url(s)).stream().filter(c -> !c.passed()).toList();
            assertThat(failed).extracting(LiveReport.Check::name).containsExactly("private/link-local host");
            assertThat(failed.get(0).severity()).isEqualTo("hardening");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void unreachableAppBecomesFailedChecksNotAnException() {
        List<LiveReport.Check> checks = LiveProbes.run("http://localhost:1");
        assertThat(checks).isNotEmpty().noneMatch(LiveReport.Check::passed);
    }

    @Test
    void executorIsAdvisoryWhenTheAppCannotStart() {
        LiveCheckExecutor exec = new LiveCheckExecutor(() -> {
            throw new IllegalStateException("package failed (exit 1)");
        });
        NodeResult r = exec.run(null);
        assertThat(r.failure()).isNull();
        LiveReport report = Json.readOrNull(r.artifacts().get(StageGraph.LIVE_REPORT), LiveReport.class);
        assertThat(report.started()).isFalse();
        assertThat(report.note()).contains("package failed");
    }

    @Test
    void executorReportsProbeResultsAndClosesTheApp() throws Exception {
        HttpServer s = stub(false);
        boolean[] closed = {false};
        LiveAppRunner runner = () -> new LiveAppRunner.Running() {
            public String baseUrl() {
                return url(s);
            }

            public void close() {
                closed[0] = true;
            }
        };
        try {
            NodeResult r = new LiveCheckExecutor(runner).run(null);
            LiveReport report = Json.readOrNull(r.artifacts().get(StageGraph.LIVE_REPORT), LiveReport.class);
            assertThat(report.started()).isTrue();
            assertThat(report.failedCount()).isZero();
            assertThat(closed[0]).isTrue();
        } finally {
            s.stop(0);
        }
    }
}
