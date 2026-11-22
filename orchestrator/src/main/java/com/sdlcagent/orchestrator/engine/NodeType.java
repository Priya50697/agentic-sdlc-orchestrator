package com.sdlcagent.orchestrator.engine;

/** JOIN nodes have no agent: they pass as soon as all their dependencies have passed (synchronization barrier). */
public enum NodeType { STAGE, TASK, JOIN }
