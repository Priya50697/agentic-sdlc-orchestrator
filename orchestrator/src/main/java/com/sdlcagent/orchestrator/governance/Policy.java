package com.sdlcagent.orchestrator.governance;

import java.util.List;

public interface Policy {
    String id();

    List<Violation> evaluate(PolicyInput input);
}
