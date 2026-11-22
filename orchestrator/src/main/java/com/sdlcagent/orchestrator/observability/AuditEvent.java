package com.sdlcagent.orchestrator.observability;

import java.time.Instant;
import java.util.Map;

/** One line of audit.jsonl. hash = sha256(prevHash + canonical JSON of every other field). */
public record AuditEvent(long seq, Instant ts, String runId, AuditEventType type, String nodeId,
                         Map<String, Object> data, String prevHash, String hash) {
}
