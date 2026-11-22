package com.sdlcagent.orchestrator.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained JSONL audit log for one run. Each event embeds the previous event's hash,
 * so any edit, deletion or reordering is detectable by {@link #verify(Path)}.
 */
public class AuditLogger {
    public static final String GENESIS = "0".repeat(64);

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public record Verification(boolean valid, long events, String message) {}

    private final Path file;
    private final String runId;
    private final Clock clock;
    private long seq;
    private String lastHash;

    public AuditLogger(Path file, String runId, Clock clock) {
        this.file = file;
        this.runId = runId;
        this.clock = clock;
        try {
            Files.createDirectories(file.getParent());
            // Resume an existing chain after a crash/restart.
            List<AuditEvent> existing = readAll(file);
            this.seq = existing.size();
            this.lastHash = existing.isEmpty() ? GENESIS : existing.get(existing.size() - 1).hash();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized AuditEvent log(AuditEventType type, String nodeId, Map<String, Object> data) {
        Instant ts = clock.instant();
        Map<String, Object> safeData = data == null ? Map.of() : data;
        long next = seq + 1;
        String hash = computeHash(lastHash, next, ts, runId, type, nodeId, safeData);
        AuditEvent event = new AuditEvent(next, ts, runId, type, nodeId, safeData, lastHash, hash);
        try {
            Files.writeString(file, MAPPER.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append audit event", e);
        }
        seq = next;
        lastHash = hash;
        return event;
    }

    /** Null-tolerant key/value helper: data("a", 1, "b", null). */
    public static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public AuditEvent log(AuditEventType type, String nodeId) {
        return log(type, nodeId, Map.of());
    }

    public static List<AuditEvent> readAll(Path file) throws IOException {
        List<AuditEvent> events = new ArrayList<>();
        if (!Files.exists(file)) return events;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) events.add(MAPPER.readValue(line, AuditEvent.class));
        }
        return events;
    }

    public static Verification verify(Path file) {
        List<AuditEvent> events;
        try {
            events = readAll(file);
        } catch (IOException e) {
            return new Verification(false, 0, "unreadable audit log: " + e.getMessage());
        }
        String prev = GENESIS;
        long expectedSeq = 1;
        for (AuditEvent e : events) {
            if (e.seq() != expectedSeq) return new Verification(false, expectedSeq - 1, "sequence gap at event " + e.seq());
            if (!prev.equals(e.prevHash())) return new Verification(false, expectedSeq - 1, "broken chain at event " + e.seq());
            String h = computeHash(e.prevHash(), e.seq(), e.ts(), e.runId(), e.type(), e.nodeId(), e.data());
            if (!h.equals(e.hash())) return new Verification(false, expectedSeq - 1, "tampered content at event " + e.seq());
            prev = e.hash();
            expectedSeq++;
        }
        return new Verification(true, events.size(), "ok");
    }

    private static String computeHash(String prevHash, long seq, Instant ts, String runId,
                                      AuditEventType type, String nodeId, Map<String, Object> data) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("seq", seq);
            canonical.put("ts", ts.toString());
            canonical.put("runId", runId);
            canonical.put("type", type.name());
            canonical.put("nodeId", nodeId);
            // Round-trip through a tree so values read back from disk hash identically to values written.
            JsonNode dataTree = MAPPER.valueToTree(data);
            canonical.put("data", dataTree);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prevHash.getBytes(StandardCharsets.UTF_8));
            md.update(MAPPER.writeValueAsBytes(canonical));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
