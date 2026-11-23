package com.sdlcagent.orchestrator.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.context.CodeIndex.ClassInfo;
import com.sdlcagent.orchestrator.context.ContextAssembler.Section;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextTest {
    @TempDir Path ws;

    private void write(String rel, String content) throws IOException {
        Path p = ws.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void indexesClassesMethodsAndEndpoints() throws IOException {
        write("src/main/java/com/sdlcagent/urlshortener/LinkController.java", """
                package com.sdlcagent.urlshortener;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping("/api/links")
                public class LinkController {
                    @PostMapping
                    public String create(@RequestBody String url) { return url; }

                    @GetMapping("/{code}/stats")
                    public int stats(@PathVariable String code) { return 0; }

                    @RequestMapping(value = "/{code}", method = RequestMethod.DELETE)
                    public void remove(@PathVariable String code) { }

                    private void hidden() { }
                }
                """);
        write("src/test/java/com/sdlcagent/urlshortener/LinkControllerTest.java",
                "package com.sdlcagent.urlshortener;\nclass LinkControllerTest { void t() {} }\n");
        write("target/generated/Skip.java", "class Skip {}");

        CodeIndex idx = new CodeIndexer().index(ws);

        assertThat(idx.classes()).extracting(ClassInfo::name).containsExactly("LinkController", "LinkControllerTest");
        ClassInfo c = idx.classes().get(0);
        assertThat(c.annotations()).contains("@RestController", "@RequestMapping");
        assertThat(c.methods()).anyMatch(m -> m.contains("create(")).noneMatch(m -> m.contains("hidden"));
        assertThat(idx.endpoints()).extracting(e -> e.verb() + " " + e.path())
                .containsExactlyInAnyOrder("POST /api/links", "GET /api/links/{code}/stats", "DELETE /api/links/{code}");
        assertThat(idx.hasFile("src/main/java/com/sdlcagent/urlshortener/LinkController.java")).isTrue();
        assertThat(idx.hasClass("com.sdlcagent.urlshortener.LinkController")).isTrue();
        assertThat(idx.hasClass("Nope")).isFalse();
        assertThat(idx.summary(10_000)).contains("GET /api/links/{code}/stats").contains("LinkController");
    }

    @Test
    void indexesNonPrivateConstructorsAndRecordComponents() throws IOException {
        write("src/main/java/com/sdlcagent/urlshortener/dao/ShortUrl.java", """
                package com.sdlcagent.urlshortener.dao;
                public class ShortUrl {
                    protected ShortUrl() { }
                    public ShortUrl(String code, String originalUrl) { }
                    private ShortUrl(int secret) { }
                }
                """);
        write("src/main/java/com/sdlcagent/urlshortener/dto/Req.java",
                "package com.sdlcagent.urlshortener.dto;\npublic record Req(String url, int ttl) { }\n");
        CodeIndex idx = new CodeIndexer().index(ws);
        ClassInfo entity = idx.classes().stream().filter(c -> c.name().equals("ShortUrl")).findFirst().orElseThrow();
        assertThat(entity.constructors()).hasSize(2)
                .anyMatch(k -> k.contains("ShortUrl(String code, String originalUrl)"))
                .noneMatch(k -> k.contains("secret"));
        ClassInfo rec = idx.classes().stream().filter(c -> c.name().equals("Req")).findFirst().orElseThrow();
        assertThat(rec.constructors()).contains("Req(String url, int ttl)");
        assertThat(idx.summary(10_000)).contains("constructor public ShortUrl(String code, String originalUrl)");
    }

    @Test
    void brokenSourceFallsBackToRegex() throws IOException {
        write("src/main/java/com/sdlcagent/urlshortener/Broken.java",
                "package com.sdlcagent.urlshortener;\n@Service\npublic class Broken {\n  void x( {\n");
        CodeIndex idx = new CodeIndexer().index(ws);
        assertThat(idx.classes()).hasSize(1);
        ClassInfo c = idx.classes().get(0);
        assertThat(c.parsed()).isFalse();
        assertThat(c.qualifiedName()).isEqualTo("com.sdlcagent.urlshortener.Broken");
        assertThat(c.annotations()).contains("@Service");
        assertThat(idx.summary(1000)).contains("regex fallback");
    }

    @Test
    void summaryRespectsBudgetByDroppingWholeClasses() throws IOException {
        for (int i = 0; i < 20; i++) {
            write("src/main/java/p/C" + i + ".java", "package p;\npublic class C" + i + " { public void m() {} }\n");
        }
        String s = new CodeIndexer().index(ws).summary(400);
        assertThat(s.length()).isLessThan(600);
        assertThat(s).contains("more classes omitted");
    }

    @Test
    void assemblerKeepsEverythingWhenItFits() {
        String out = new ContextAssembler(1000).assemble(List.of(new Section("A", "aaa"), new Section("B", "bbb")));
        assertThat(out).contains("=== A ===\naaa").contains("=== B ===\nbbb").doesNotContain("truncated");
    }

    @Test
    void assemblerTrimsLargestSectionFirstAndStaysInBudget() {
        String spec = "S".repeat(100);
        String code = "C".repeat(10_000);
        int budget = 600;
        String out = new ContextAssembler(budget).assemble(List.of(new Section("SPEC", spec), new Section("CODE", code)));
        assertThat(out.length()).isLessThanOrEqualTo(budget);
        assertThat(out).contains(spec).contains("truncated");
    }
}
