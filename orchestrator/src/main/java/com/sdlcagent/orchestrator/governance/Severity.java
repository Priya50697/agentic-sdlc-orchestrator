package com.sdlcagent.orchestrator.governance;

/** LOW/MEDIUM: audited only. HIGH: the attempt fails and is retried with feedback. CRITICAL: safe-stop. */
public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
