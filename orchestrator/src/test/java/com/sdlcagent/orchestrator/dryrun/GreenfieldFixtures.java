package com.sdlcagent.orchestrator.dryrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdlcagent.orchestrator.llm.LlmRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written replies for every agent, keyed by the agent's system prompt. Deliberate imperfections exercise the
 * recovery paths: the architect's first reply is not JSON (repair), and T3 first proposes a file it does not own.
 */
final class GreenfieldFixtures {
    static final String PKG = "src/main/java/com/sdlcagent/urlshortener/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TASK_ID = Pattern.compile("(?m)^id: (T\\d+)");

    final AtomicInteger architectCalls = new AtomicInteger();
    final AtomicInteger t3Calls = new AtomicInteger();

    String reply(LlmRequest req) {
        String sys = req.system();
        if (sys.contains("Requirements Analyst")) return json(spec());
        if (sys.contains("Software Architect")) {
            return architectCalls.incrementAndGet() == 1 ? "Here is my design (forgot the JSON, sorry)." : json(plan());
        }
        if (sys.contains("the Implementer")) return implementer(req.user());
        if (sys.contains("Test Engineer")) return json(tests());
        if (sys.contains("Documentation Writer")) return json(docs());
        if (sys.contains("Release Reviewer")) {
            return json(Map.of("verdict", "READY", "summary", "All four acceptance criteria are covered by passing tests.",
                    "risks", List.of("In-memory H2 only"), "followUps", List.of("Add rate limiting")));
        }
        throw new IllegalStateException("no fixture for system prompt: " + sys.substring(0, Math.min(60, sys.length())));
    }

    private String implementer(String user) {
        Matcher m = TASK_ID.matcher(user);
        if (!m.find()) throw new IllegalStateException("no task id in implementer prompt");
        return switch (m.group(1)) {
            case "T1" -> files("entity and repository", Map.of(
                    PKG + "model/ShortUrl.java", SHORT_URL,
                    PKG + "repository/ShortUrlRepository.java", REPOSITORY));
            case "T2" -> files("safety provider", Map.of(
                    PKG + "client/UrlSafetyProvider.java", PROVIDER,
                    PKG + "client/StubUrlSafetyProvider.java", STUB));
            case "T3" -> t3Calls.incrementAndGet() == 1
                    ? files("service", Map.of(PKG + "service/LinkHelper.java", "package com.sdlcagent.urlshortener.service; class LinkHelper {}"))
                    : files("service", Map.of(PKG + "service/LinkService.java", SERVICE));
            case "T4" -> files("controller", Map.of(PKG + "api/LinkController.java", CONTROLLER));
            default -> throw new IllegalStateException("unknown task " + m.group(1));
        };
    }

    // ---- structured replies

    private static Map<String, Object> spec() {
        return Map.of("title", "URL shortener", "summary", "Shorten, redirect, count clicks, reject unsafe URLs.",
                "stories", List.of(Map.of("id", "S1", "text", "As a user I want short links that redirect and are counted")),
                "acceptanceCriteria", List.of(
                        ac("AC-1", "POST /api/urls with a valid URL returns 201 and a short code"),
                        ac("AC-2", "GET /{code} responds 302 with the original URL in Location"),
                        ac("AC-3", "GET /api/urls/{code}/stats returns the number of redirects so far"),
                        ac("AC-4", "POST /api/urls with a URL the safety provider rejects returns 400")),
                "ambiguities", List.of(Map.of("id", "A1", "question", "Code length?", "severity", "MINOR", "proposedDefault", "7 chars")),
                "nonFunctional", List.of("H2 persistence"));
    }

    private static Map<String, Object> ac(String id, String text) {
        return Map.of("id", id, "storyId", "S1", "text", text);
    }

    private static Map<String, Object> plan() {
        return Map.of("overview", "Layered Spring Boot service",
                "components", List.of(
                        Map.of("name", "ShortUrl", "layer", "model", "path", PKG + "model/ShortUrl.java", "responsibility", "entity"),
                        Map.of("name", "LinkController", "layer", "api", "path", PKG + "api/LinkController.java", "responsibility", "HTTP")),
                "endpoints", List.of(
                        Map.of("verb", "POST", "path", "/api/urls", "description", "create"),
                        Map.of("verb", "GET", "path", "/{code}", "description", "redirect"),
                        Map.of("verb", "GET", "path", "/api/urls/{code}/stats", "description", "stats")),
                "dataModel", List.of("ShortUrl(id, code, longUrl, createdAt, clicks)"),
                "tasks", List.of(
                        task("T1", List.of(), List.of("model/ShortUrl.java", "repository/ShortUrlRepository.java"), "AC-3"),
                        task("T2", List.of(), List.of("client/UrlSafetyProvider.java", "client/StubUrlSafetyProvider.java"), "AC-4"),
                        task("T3", List.of("T1", "T2"), List.of("service/LinkService.java"), "AC-1", "AC-2"),
                        task("T4", List.of("T3"), List.of("api/LinkController.java"), "AC-1", "AC-2", "AC-3", "AC-4")));
    }

    private static Map<String, Object> task(String id, List<String> deps, List<String> paths, String... acs) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("description", "task " + id);
        t.put("dependsOn", deps);
        t.put("targetPaths", paths.stream().map(p -> PKG + p).toList());
        t.put("acceptanceCriteria", List.of(acs));
        return t;
    }

    private static Map<String, Object> tests() {
        return Map.of("summary", "MockMvc tests for AC-1..AC-4", "files", List.of(Map.of(
                "path", "src/test/java/com/sdlcagent/urlshortener/LinkApiTest.java", "op", "CREATE", "content", LINK_TEST)));
    }

    private static Map<String, Object> docs() {
        return Map.of("summary", "README", "files", List.of(Map.of("path", "README.md", "op", "CREATE", "content", README)));
    }

    private static String files(String summary, Map<String, String> content) {
        List<Map<String, String>> list = content.entrySet().stream()
                .map(e -> Map.of("path", e.getKey(), "op", "CREATE", "content", e.getValue())).toList();
        return json(Map.of("summary", summary, "files", list));
    }

    private static String json(Object o) {
        try {
            return "```json\n" + JSON.writeValueAsString(o) + "\n```";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- generated sources (kept compilable so SCAFFOLD_LIVE-style runs can reuse them)

    static final String SHORT_URL = """
            package com.sdlcagent.urlshortener.model;

            import jakarta.persistence.Column;
            import jakarta.persistence.Entity;
            import jakarta.persistence.GeneratedValue;
            import jakarta.persistence.GenerationType;
            import jakarta.persistence.Id;
            import java.time.Instant;

            @Entity
            public class ShortUrl {
                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;
                @Column(unique = true, nullable = false)
                private String code;
                @Column(nullable = false, length = 2048)
                private String longUrl;
                private Instant createdAt = Instant.now();
                private long clicks;

                protected ShortUrl() {}

                public ShortUrl(String code, String longUrl) {
                    this.code = code;
                    this.longUrl = longUrl;
                }

                public String getCode() { return code; }
                public String getLongUrl() { return longUrl; }
                public Instant getCreatedAt() { return createdAt; }
                public long getClicks() { return clicks; }
                public void recordClick() { clicks++; }
            }
            """;

    static final String REPOSITORY = """
            package com.sdlcagent.urlshortener.repository;

            import com.sdlcagent.urlshortener.model.ShortUrl;
            import java.util.Optional;
            import org.springframework.data.jpa.repository.JpaRepository;

            public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
                Optional<ShortUrl> findByCode(String code);
            }
            """;

    static final String PROVIDER = """
            package com.sdlcagent.urlshortener.client;

            public interface UrlSafetyProvider {
                boolean isSafe(String url);
            }
            """;

    static final String STUB = """
            package com.sdlcagent.urlshortener.client;

            import org.springframework.stereotype.Component;

            /** Local stand-in for the outbound URL-safety provider. */
            @Component
            public class StubUrlSafetyProvider implements UrlSafetyProvider {
                @Override
                public boolean isSafe(String url) {
                    return !url.toLowerCase().contains("malware");
                }
            }
            """;

    static final String SERVICE = """
            package com.sdlcagent.urlshortener.service;

            import com.sdlcagent.urlshortener.client.UrlSafetyProvider;
            import com.sdlcagent.urlshortener.model.ShortUrl;
            import com.sdlcagent.urlshortener.repository.ShortUrlRepository;
            import java.net.URI;
            import java.security.SecureRandom;
            import java.util.Optional;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            @Service
            public class LinkService {
                private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
                private final SecureRandom random = new SecureRandom();
                private final ShortUrlRepository repository;
                private final UrlSafetyProvider safety;

                public LinkService(ShortUrlRepository repository, UrlSafetyProvider safety) {
                    this.repository = repository;
                    this.safety = safety;
                }

                public static class UnsafeUrlException extends RuntimeException {
                    public UnsafeUrlException(String message) { super(message); }
                }

                public ShortUrl shorten(String longUrl) {
                    URI uri;
                    try {
                        uri = URI.create(longUrl);
                    } catch (IllegalArgumentException e) {
                        throw new UnsafeUrlException("not a valid URL");
                    }
                    if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
                        throw new UnsafeUrlException("only http and https URLs are accepted");
                    }
                    if (!safety.isSafe(longUrl)) throw new UnsafeUrlException("URL rejected by the safety provider");
                    return repository.save(new ShortUrl(newCode(), longUrl));
                }

                @Transactional
                public Optional<String> resolve(String code) {
                    return repository.findByCode(code).map(u -> {
                        u.recordClick();
                        repository.save(u);
                        return u.getLongUrl();
                    });
                }

                public Optional<ShortUrl> find(String code) {
                    return repository.findByCode(code);
                }

                private String newCode() {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 7; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                    return sb.toString();
                }
            }
            """;

    static final String CONTROLLER = """
            package com.sdlcagent.urlshortener.api;

            import com.sdlcagent.urlshortener.model.ShortUrl;
            import com.sdlcagent.urlshortener.service.LinkService;
            import jakarta.validation.Valid;
            import jakarta.validation.constraints.NotBlank;
            import java.net.URI;
            import java.util.Map;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PathVariable;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class LinkController {
                public record CreateRequest(@NotBlank String url) {}

                private final LinkService service;

                public LinkController(LinkService service) {
                    this.service = service;
                }

                @PostMapping("/api/urls")
                public ResponseEntity<?> create(@Valid @RequestBody CreateRequest request) {
                    try {
                        ShortUrl created = service.shorten(request.url());
                        return ResponseEntity.status(201).body(Map.of("code", created.getCode(), "longUrl", created.getLongUrl()));
                    } catch (LinkService.UnsafeUrlException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                    }
                }

                @GetMapping("/{code}")
                public ResponseEntity<Void> redirect(@PathVariable String code) {
                    return service.resolve(code)
                            .map(url -> ResponseEntity.status(302).location(URI.create(url)).<Void>build())
                            .orElse(ResponseEntity.notFound().build());
                }

                @GetMapping("/api/urls/{code}/stats")
                public ResponseEntity<?> stats(@PathVariable String code) {
                    return service.find(code)
                            .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of("code", u.getCode(), "clicks", u.getClicks())))
                            .orElse(ResponseEntity.notFound().build());
                }
            }
            """;

    static final String LINK_TEST = """
            package com.sdlcagent.urlshortener;

            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
            import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.http.MediaType;
            import org.springframework.test.web.servlet.MockMvc;

            @SpringBootTest
            @AutoConfigureMockMvc
            class LinkApiTest {
                @Autowired MockMvc mvc;
                @Autowired ObjectMapper json;

                private String create(String url) throws Exception {
                    String body = mvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"" + url + "\\"}"))
                            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                    return json.readTree(body).get("code").asText();
                }

                @Test
                @DisplayName("AC-1 POST /api/urls returns 201 and a short code")
                void createReturnsCode() throws Exception {
                    create("https://example.com/a");
                }

                @Test
                @DisplayName("AC-2 GET /{code} redirects with 302")
                void redirects() throws Exception {
                    String code = create("https://example.com/b");
                    mvc.perform(get("/" + code)).andExpect(status().isFound())
                            .andExpect(header().string("Location", "https://example.com/b"));
                }

                @Test
                @DisplayName("AC-3 stats count redirects")
                void statsCountClicks() throws Exception {
                    String code = create("https://example.com/c");
                    mvc.perform(get("/" + code));
                    mvc.perform(get("/api/urls/" + code + "/stats")).andExpect(status().isOk())
                            .andExpect(jsonPath("$.clicks").value(1));
                }

                @Test
                @DisplayName("AC-4 unsafe URL is rejected with 400")
                void unsafeRejected() throws Exception {
                    mvc.perform(post("/api/urls").contentType(MediaType.APPLICATION_JSON)
                                    .content("{\\"url\\":\\"https://malware.example\\"}"))
                            .andExpect(status().isBadRequest());
                }
            }
            """;

    static final String README = """
            # URL shortener

            ## Overview
            Shortens URLs, redirects, counts clicks and rejects unsafe targets. Data lives in H2.

            ## API
            - `POST /api/urls` creates a short code (201) or rejects an unsafe URL (400).
            - `GET /{code}` redirects with 302 and counts a click.
            - `GET /api/urls/{code}/stats` returns the click count.

            ## Running
            `mvnw spring-boot:run`

            ## Testing
            `mvnw test`
            """;
}
