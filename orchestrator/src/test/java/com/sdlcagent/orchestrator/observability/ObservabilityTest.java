package com.sdlcagent.orchestrator.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservabilityTest {

    /** Clock that advances 1s per reading, making durations deterministic. */
    static class TickingClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { Instant i = now; now = now.plusSeconds(1); return i; }
    }

    @Test
    void chainVerifiesThenDetectsTamperingAndResumes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.jsonl");
        var log = new AuditLogger(file, "run1", new TickingClock());
        log.log(AuditEventType.RUN_STARTED, null);
        log.log(AuditEventType.AGENT_END, "req", Map.of("inputTokens", 10, "nested", Map.of("b", 1, "a", 2)));
        log.log(AuditEventType.RUN_COMPLETED, null);
        assertThat(AuditLogger.verify(file).valid()).isTrue();
        assertThat(AuditLogger.verify(file).events()).isEqualTo(3);

        // A fresh logger on the same file continues the chain.
        new AuditLogger(file, "run1", new TickingClock()).log(AuditEventType.CHECKPOINT, "req");
        assertThat(AuditLogger.verify(file)).satisfies(v -> {
            assertThat(v.valid()).isTrue();
            assertThat(v.events()).isEqualTo(4);
        });

        // Tamper with event content.
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\"inputTokens\":10", "\"inputTokens\":99");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        var bad = AuditLogger.verify(file);
        assertThat(bad.valid()).isFalse();
        assertThat(bad.message()).contains("tampered");
    }

    @Test
    void detectsDeletedEvent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("audit.jsonl");
        var log = new AuditLogger(file, "r", new TickingClock());
        log.log(AuditEventType.RUN_STARTED, null);
        log.log(AuditEventType.CHECKPOINT, "a");
        log.log(AuditEventType.RUN_COMPLETED, null);
        List<String> lines = Files.readAllLines(file);
        Files.write(file, List.of(lines.get(0), lines.get(2)));
        assertThat(AuditLogger.verify(file).valid()).isFalse();
    }

    @Test
    void metricsDeriveSuccessRetryMttrAndLatency(@TempDir Path dir) {
        Path file = dir.resolve("audit.jsonl");
        var log = new AuditLogger(file, "r", new TickingClock());          // each event = +1s
        log.log(AuditEventType.RUN_STARTED, null);                           // t0
        log.log(AuditEventType.AGENT_END, "impl",
                Map.of("inputTokens", 100, "outputTokens", 50, "costUsd", 0.01)); // t1
        log.log(AuditEventType.NODE_FAILED, "impl");                         // t2  failure
        log.log(AuditEventType.RETRY, "impl");                               // t3
        log.log(AuditEventType.AGENT_END, "impl", Map.of("cacheHit", true)); // t4
        log.log(AuditEventType.NODE_PASSED, "impl");                         // t5  recovered: 3s
        log.log(AuditEventType.NODE_PASSED, "docs");                         // t6
        log.log(AuditEventType.RUN_COMPLETED, null);                         // t7

        var s = new MetricsService().compute(file);
        assertThat(s.outcome()).isEqualTo("COMPLETED");
        assertThat(s.nodesPassed()).isEqualTo(2);
        assertThat(s.nodesFailed()).isEqualTo(1);
        assertThat(s.nodeSuccessRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.retries()).isEqualTo(1);
        assertThat(s.llmCalls()).isEqualTo(1);           // cache hit not counted
        assertThat(s.inputTokens()).isEqualTo(100);
        assertThat(s.costUsd()).isEqualTo(0.01);
        assertThat(s.mttrMillis()).isEqualTo(3000.0);
        assertThat(s.e2eLatencyMillis()).isEqualTo(7000L);
    }
}
