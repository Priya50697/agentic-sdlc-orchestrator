package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.governance.Gate;

/** A gate that registers itself under {@link #name()}; the registrar wires it into every engine. */
public interface GateBean extends Gate {
    String name();
}
