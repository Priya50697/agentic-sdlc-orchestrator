package com.sdlcagent.orchestrator.agents.testing;

import com.sdlcagent.orchestrator.agents.testing.TestSuite.SupersededTest;
import com.sdlcagent.orchestrator.workspace.TestInventory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic rule for brownfield test changes: a baseline test may not disappear, and may only be changed when it
 * is declared as superseded with a reason. Declaring a test that was not actually changed is also rejected, so the
 * declaration always matches what the diff does.
 */
public final class BaselineGuard {
    private BaselineGuard() {}

    /**
     * @param baseline test id -> normalized body at the start of the run
     * @param after    same, for the test sources as they would be after the proposed change
     * @return problems (empty = acceptable)
     */
    public static List<String> check(Map<String, String> baseline, Map<String, String> after,
                                     List<SupersededTest> declared) {
        List<String> problems = new ArrayList<>();
        Set<String> declaredIds = new HashSet<>();
        for (SupersededTest d : declared) {
            if (d.testId() == null || d.testId().isBlank()) {
                problems.add("a supersededTests entry has no \"testId\"; use {\"testId\": \"ClassName#methodName\", \"reason\": \"...\"}");
                continue;
            }
            String id = TestInventory.normalizeId(d.testId());
            if (d.reason() == null || d.reason().isBlank()) {
                problems.add("supersededTests entry for " + id + " has no reason");
            }
            declaredIds.add(id);
        }
        Set<String> changed = new HashSet<>();
        baseline.forEach((id, body) -> {
            String now = after.get(id);
            if (now == null) {
                problems.add("baseline test removed or renamed: " + id + " (existing tests must keep existing)");
            } else if (!now.equals(body)) {
                changed.add(id);
                if (!declaredIds.contains(id)) {
                    problems.add("baseline test " + id + " was changed but is not declared in supersededTests;"
                            + " if the new behaviour really contradicts it, declare it with a reason, otherwise restore it");
                }
            }
        });
        for (String id : declaredIds) {
            if (!baseline.containsKey(id)) {
                problems.add("supersededTests lists " + id + ", which is not a baseline test");
            } else if (!changed.contains(id)) {
                problems.add("supersededTests lists " + id + " but the proposed change does not alter it");
            }
        }
        return problems;
    }
}
