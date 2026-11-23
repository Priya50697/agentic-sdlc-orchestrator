package com.sdlcagent.orchestrator.dryrun;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.workspace.MavenRunner;
import com.sdlcagent.orchestrator.workspace.Scaffolder;
import com.sdlcagent.orchestrator.workspace.SurefireParser;
import com.sdlcagent.orchestrator.workspace.WorkspaceGit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in (SCAFFOLD_LIVE=1, needs the Maven repo): writes the dry-run fixture sources into a real scaffold and runs the
 * real {@code mvnw test}, so the scripted "generated app" is proven to compile and pass its own tests.
 */
@EnabledIfEnvironmentVariable(named = "SCAFFOLD_LIVE", matches = "1")
class FixtureBuildLiveTest {
    @TempDir Path dir;

    @Test
    void fixtureSourcesCompileAndTheirTestsPassUnderRealMaven() throws Exception {
        Path ws = dir.resolve("ws");
        new Scaffolder(ws, new WorkspaceGit(dir.resolve("g.git"), ws)).scaffold();
        String pkg = GreenfieldFixtures.PKG;
        Map<String, String> files = Map.of(
                pkg + "dao/ShortUrl.java", GreenfieldFixtures.SHORT_URL,
                pkg + "dao/ShortUrlRepository.java", GreenfieldFixtures.REPOSITORY,
                pkg + "provider/UrlSafetyProvider.java", GreenfieldFixtures.PROVIDER,
                pkg + "provider/StubUrlSafetyProvider.java", GreenfieldFixtures.STUB,
                pkg + "service/LinkService.java", GreenfieldFixtures.SERVICE,
                pkg + "controller/LinkController.java", GreenfieldFixtures.CONTROLLER,
                "src/test/java/com/sdlcagent/urlshortener/LinkApiTest.java", GreenfieldFixtures.LINK_TEST);
        for (var e : files.entrySet()) {
            Path f = ws.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        MavenRunner.Result r = new MavenRunner(ws, Duration.ofMinutes(10)).test();
        assertThat(r.ok()).as(r.output()).isTrue();
        SurefireParser.TestSummary s = SurefireParser.parseReports(ws);
        assertThat(s.total()).isEqualTo(4);
        assertThat(s.allPassed()).isTrue();
    }
}
