package com.sdlcagent.orchestrator.context;

import com.sdlcagent.orchestrator.engine.ArtifactStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the text an agent sees: only the artifacts it asked for, plus optional code-index summary and the full
 * text of target files, all under one character budget. When over budget the largest sections are trimmed first
 * (water-filling), so small, important sections like the spec survive intact.
 */
public class ContextAssembler {
    private static final String CUT = "\n...[truncated to fit context budget]";

    public record Section(String title, String content) {}

    private final int maxChars;

    public ContextAssembler(int maxChars) {
        this.maxChars = maxChars;
    }

    public static Section artifact(ArtifactStore store, String id) {
        return new Section("ARTIFACT " + id, store.get(id).orElse("(not available)"));
    }

    public String assemble(List<Section> sections) {
        int overhead = sections.stream().mapToInt(s -> header(s).length() + 1).sum();
        int available = Math.max(0, maxChars - overhead);
        int cap = capFor(sections, available);
        List<String> parts = new ArrayList<>();
        for (Section s : sections) {
            String body = s.content() == null ? "" : s.content();
            if (body.length() > cap) {
                int keep = Math.max(0, cap - CUT.length());
                body = body.substring(0, keep) + CUT;
            }
            parts.add(header(s) + body + "\n");
        }
        return String.join("", parts);
    }

    private static String header(Section s) {
        return "=== " + s.title() + " ===\n";
    }

    /** Largest per-section length such that the total fits the budget. */
    private static int capFor(List<Section> sections, int available) {
        int total = sections.stream().mapToInt(s -> len(s)).sum();
        if (total <= available) return Integer.MAX_VALUE;
        int lo = 0, hi = sections.stream().mapToInt(s -> len(s)).max().orElse(0);
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            long sum = 0;
            for (Section s : sections) sum += Math.min(len(s), mid);
            if (sum <= available) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private static int len(Section s) {
        return s.content() == null ? 0 : s.content().length();
    }
}
