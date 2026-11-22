package com.sdlcagent.orchestrator.engine;

/**
 * Why an attempt failed. {@code signature} identifies the failing thing (e.g. a test id) so repeats can be
 * detected; {@code ownerNodeId} is the node that owns the offending code (route-back target), may be null.
 */
public record FailureInfo(FailureKind kind, String message, String signature, String ownerNodeId) {
    public static FailureInfo of(FailureKind kind, String message) {
        return new FailureInfo(kind, message, null, null);
    }
}
