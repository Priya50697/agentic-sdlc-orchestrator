package com.sdlcagent.orchestrator.agents.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sdlcagent.orchestrator.agents.base.Json;
import com.sdlcagent.orchestrator.agents.testing.TestSuite.SupersededTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The three shapes the model actually produced for supersededTests in run-20261124-130050-b1f6. */
class SupersededParsingTest {
    static final String ID = "UrlShortenerAcceptanceTest#ac6_noAnalyticsStored";

    static List<SupersededTest> parse(String arrayJson) {
        TestSuite s = Json.readOrNull("{\"files\":[],\"supersededTests\":" + arrayJson + "}", TestSuite.class);
        assertThat(s).as("suite must parse").isNotNull();
        return s.supersededTests();
    }

    @Test
    void documentedShape() {
        assertThat(parse("[{\"testId\":\"" + ID + "\",\"reason\":\"r\"}]"))
                .containsExactly(new SupersededTest(ID, "r"));
    }

    @Test
    void testKeyInsteadOfTestId() {
        assertThat(parse("[{\"test\":\"" + ID + "\",\"reason\":\"r\"}]")).containsExactly(new SupersededTest(ID, "r"));
    }

    @Test
    void classAndMethodKeys() {
        assertThat(parse("[{\"testClass\":\"UrlShortenerAcceptanceTest\",\"testMethod\":\"ac6_noAnalyticsStored\",\"reason\":\"r\"}]"))
                .containsExactly(new SupersededTest(ID, "r"));
    }

    @Test
    void idKeyWinsOverClassAndMethod() {
        assertThat(parse("[{\"testClass\":\"A\",\"testMethod\":\"b\",\"id\":\"" + ID + "\",\"reason\":\"r\"}]"))
                .containsExactly(new SupersededTest(ID, "r"));
    }

    @Test
    void bareStringParsesButHasNoReasonSoTheGuardAsksForOne() {
        List<SupersededTest> parsed = parse("[\"" + ID + "\"]");
        assertThat(parsed).containsExactly(new SupersededTest(ID, null));
        assertThat(BaselineGuard.check(java.util.Map.of(ID, "a"), java.util.Map.of(ID, "b"), parsed))
                .anyMatch(p -> p.contains("has no reason"));
    }

    @Test
    void missingIdIsReportedClearlyNotAsAnEmptyName() {
        List<SupersededTest> parsed = parse("[{\"reason\":\"r\"}]");
        assertThat(BaselineGuard.check(java.util.Map.of(), java.util.Map.of(), parsed))
                .singleElement().asString().contains("no \"testId\"").doesNotContain("which is not a baseline test");
    }
}
