package com.sdlcagent.orchestrator.cli;

import com.sdlcagent.orchestrator.engine.RunSnapshot;
import com.sdlcagent.orchestrator.governance.ApprovalRequest;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Channel;
import com.sdlcagent.orchestrator.governance.ApprovalRequest.Decision;
import com.sdlcagent.orchestrator.service.RunService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;

/**
 * Drives one run interactively: run until idle, show each pending approval, read a decision from the operator,
 * repeat. Input/output are injected so the loop is testable. End of input safe-stops the run.
 */
public class ConsoleSession {
    private final RunService runs;
    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleSession(RunService runs, BufferedReader in, PrintStream out) {
        this.runs = runs;
        this.in = in;
        this.out = out;
    }

    public RunSnapshot drive(String runId) {
        out.println("run " + runId);
        while (true) {
            RunSnapshot s = runs.run(runId);
            if (s.state.isTerminal()) {
                out.println("run " + runId + " finished: " + s.state
                        + (s.stopReason == null ? "" : " (" + s.stopReason + ")"));
                return s;
            }
            var pending = runs.status(runId).pendingApprovals();
            if (pending.isEmpty()) {
                out.println("run idle with nothing to approve: " + s.state);
                return s;
            }
            for (ApprovalRequest p : pending) {
                if (!prompt(runId, p)) {
                    runs.abortAndWait(runId, "console input closed");
                    return runs.engine(runId).snapshot();
                }
            }
        }
    }

    /** @return false when input is exhausted */
    private boolean prompt(String runId, ApprovalRequest p) {
        out.println();
        out.println("[" + p.kind() + "] node " + p.nodeId() + " (" + p.id() + ")");
        out.println(p.summary());
        out.print("approve (a) / reject with feedback (r <text>): ");
        out.flush();
        String line = readLine();
        while (line != null) {
            String t = line.trim();
            String lower = t.toLowerCase();
            if (lower.equals("a") || lower.equals("approve")) {
                runs.resolve(runId, p.id(), Decision.APPROVE, null, Channel.CONSOLE, "console");
                return true;
            }
            if (lower.startsWith("a ")) {
                runs.resolve(runId, p.id(), Decision.APPROVE, t.substring(2).trim(), Channel.CONSOLE, "console");
                return true;
            }
            if (lower.startsWith("r")) {
                String fb = t.substring(1).trim();
                runs.resolve(runId, p.id(), Decision.REJECT, fb.isEmpty() ? null : fb, Channel.CONSOLE, "console");
                return true;
            }
            out.print("please answer 'a' or 'r <feedback>': ");
            out.flush();
            line = readLine();
        }
        return false;
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
