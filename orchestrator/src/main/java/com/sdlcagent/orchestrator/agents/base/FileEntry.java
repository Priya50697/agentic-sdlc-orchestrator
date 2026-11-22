package com.sdlcagent.orchestrator.agents.base;

import com.sdlcagent.orchestrator.governance.FileChange;
import java.util.List;

/** A file proposed by an LLM agent; op is CREATE, MODIFY or DELETE (default CREATE). */
public record FileEntry(String path, String op, String content) {

    /** Error text if the entry is unusable, else null. */
    public String problem() {
        if (path == null || path.isBlank()) return "file entry without path";
        FileChange.Op o = parseOp();
        if (o == null) return "unknown op '" + op + "' for " + path + " (use CREATE, MODIFY or DELETE)";
        if (o != FileChange.Op.DELETE && (content == null || content.isEmpty())) return "no content for " + path;
        return null;
    }

    private FileChange.Op parseOp() {
        if (op == null || op.isBlank()) return FileChange.Op.CREATE;
        try {
            return FileChange.Op.valueOf(op.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public FileChange toChange() {
        return new FileChange(path.strip().replace('\\', '/'), parseOp(), content);
    }

    public static List<FileChange> toChanges(List<FileEntry> entries) {
        return entries == null ? List.of() : entries.stream().map(FileEntry::toChange).toList();
    }

    /** First problem across the entries, or null. */
    public static String firstProblem(List<FileEntry> entries) {
        if (entries == null || entries.isEmpty()) return "no files returned";
        for (FileEntry e : entries) {
            if (e == null) return "null file entry";
            String p = e.problem();
            if (p != null) return p;
        }
        return null;
    }
}
