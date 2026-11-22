package com.sdlcagent.orchestrator.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmCoreTest {
    record Pair(String a, int b) {}

    @Test
    void fakeReturnsSequenceAndRecordsRequests() {
        var fake = FakeLlmClient.sequence("one", "two");
        assertThat(fake.complete(LlmRequest.of("s", "u")).text()).isEqualTo("one");
        assertThat(fake.complete(LlmRequest.of("s", "u")).text()).isEqualTo("two");
        assertThat(fake.complete(LlmRequest.of("s", "u")).text()).isEqualTo("two");
        assertThat(fake.callCount()).isEqualTo(3);
    }

    @Test
    void recordThenReplayServesFromCacheWithoutCallingDelegate(@TempDir Path dir) {
        var fake = FakeLlmClient.sequence("hello");
        var rec = new CachingLlmClient(fake, dir, CachingLlmClient.Mode.RECORD, "m");
        assertThat(rec.complete(LlmRequest.of("s", "u")).cacheHit()).isFalse();
        assertThat(rec.complete(LlmRequest.of("s", "u")).cacheHit()).isTrue();
        assertThat(fake.callCount()).isEqualTo(1);

        var replay = new CachingLlmClient(FakeLlmClient.sequence("x"), dir, CachingLlmClient.Mode.REPLAY, "m");
        assertThat(replay.complete(LlmRequest.of("s", "u")).text()).isEqualTo("hello");
        assertThatThrownBy(() -> replay.complete(LlmRequest.of("s", "other")))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.CACHE_MISS));
    }

    @Test
    void offModeAlwaysCallsDelegate(@TempDir Path dir) {
        var fake = FakeLlmClient.sequence("a");
        var off = new CachingLlmClient(fake, dir, CachingLlmClient.Mode.OFF, "m");
        off.complete(LlmRequest.of("s", "u"));
        off.complete(LlmRequest.of("s", "u"));
        assertThat(fake.callCount()).isEqualTo(2);
    }

    @Test
    void parserHandlesFencesAndProse() {
        var p = new JsonResponseParser();
        assertThat(p.parse("```json\n{\"a\":\"x\",\"b\":2}\n```", Pair.class)).isEqualTo(new Pair("x", 2));
        assertThat(p.parse("Sure! Here it is: {\"a\":\"y\",\"b\":3} Hope it helps", Pair.class))
                .isEqualTo(new Pair("y", 3));
        assertThatThrownBy(() -> p.parse("no json here", Pair.class)).isInstanceOf(LlmException.class);
    }
}
