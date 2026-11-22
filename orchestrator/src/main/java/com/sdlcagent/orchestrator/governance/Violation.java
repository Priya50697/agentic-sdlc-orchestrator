package com.sdlcagent.orchestrator.governance;

public record Violation(String policyId, Severity severity, String message, String path) {}
