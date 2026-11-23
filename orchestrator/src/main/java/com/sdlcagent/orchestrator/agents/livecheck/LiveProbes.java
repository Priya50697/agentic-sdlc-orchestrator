package com.sdlcagent.orchestrator.agents.livecheck;

import com.sdlcagent.orchestrator.agents.livecheck.LiveReport.Check;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP probes for the URL shortener contract (POST /shorten, GET /{code}). Never throws; failures become checks. */
public final class LiveProbes {
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
    private static final String TARGET = "https://example.com/live-check?x=1";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final List<Check> checks = new ArrayList<>();

    private LiveProbes(String baseUrl) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public static List<Check> run(String baseUrl) {
        LiveProbes p = new LiveProbes(baseUrl);
        p.all();
        return List.copyOf(p.checks);
    }

    private void all() {
        Resp created = post("{\"url\":\"" + TARGET + "\"}");
        String code = created.code();
        record("shorten valid URL", "201 + code", created, created.status == 201 && code != null, "required");

        if (code != null) {
            Resp redirect = get("/" + code);
            record("redirect known code", "302 + Location=" + TARGET, redirect,
                    redirect.status == 302 && TARGET.equals(redirect.location), "required");
        }
        Resp unknown = get("/doesnotexist0");
        record("unknown code", "404", unknown, unknown.status == 404, "required");

        expect400("blank url", "{\"url\":\"\"}", "required");
        expect400("missing url field", "{}", "required");
        expect400("non-http scheme (ftp)", "{\"url\":\"ftp://example.com\"}", "required");
        expect400("javascript scheme", "{\"url\":\"javascript:alert(1)\"}", "required");
        expect400("malformed JSON", "{bad", "required");
        expect400("private/link-local host", "{\"url\":\"http://169.254.169.254/latest\"}", "hardening");
    }

    private void expect400(String name, String body, String severity) {
        Resp r = post(body);
        record(name, "400", r, r.status == 400, severity);
    }

    private void record(String name, String expected, Resp r, boolean ok, String severity) {
        checks.add(new Check(name, expected, r.describe(), ok, severity));
    }

    private Resp post(String body) {
        return send(HttpRequest.newBuilder(URI.create(base + "/shorten")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private Resp get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build());
    }

    private Resp send(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(null), null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Resp(-1, "", null, e.toString());
        }
    }

    private record Resp(int status, String body, String location, String error) {
        String code() {
            Matcher m = CODE.matcher(body == null ? "" : body);
            return m.find() ? m.group(1) : null;
        }

        String describe() {
            if (error != null) return "error: " + error;
            String b = body == null ? "" : body.strip();
            if (b.length() > 120) b = b.substring(0, 120);
            return status + (location != null ? " Location=" + location : "") + (b.isEmpty() ? "" : " " + b);
        }
    }
}
