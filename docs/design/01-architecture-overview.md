# 01 - Architecture Overview

The orchestrator (`orchestrator/`, Java 21, Spring Boot 3.5, Maven) drives one requirement through the SDLC and
produces a working service in `url-shortener/`. The seven SDLC agents call an LLM (`claude -p` by default) to write
the specs, plans, code, tests, docs and review. Everything that *decides* (gates, policies, routing, budgets) is
deterministic Java code, not LLM output: the LLM drafts, and deterministic code accepts, rejects, retries or stops.

## 1. Two-level graph

**Stage graph** - fixed, declared in `engine/StageGraph.java`:

```
requirements --(Gate 0 when a BLOCKING ambiguity exists)--> codebase-analysis (SKIPPED for greenfield)
  --> architecture --[Gate 1: approve spec + design + task graph, or reject with feedback]-->
  --> implementation (join of the task waves)
        |--> testing  --\
        |--> docs     ---+--join--> release --[Gate 2: release sign-off]--> COMPLETED
        '--> live-check -/   (advisory, non-LLM)
```

**Task graph** - created at runtime by the Architect (`task-graph` artifact), expanded by `TaskGraphExpander` into
nodes `impl:<taskId>` grouped in waves (`TaskGraph.waves`). Independent tasks of a wave run in parallel; after each
wave a non-LLM `wave:<n>` compile gate runs `mvnw compile`. The `implementation` node is the join over all tasks.

## 2. Engine (`engine/`)

- **Single-threaded coordinator** (`WorkflowEngine`) owns every state change. Attempts run on virtual threads and
  report back through a queue, so there are no races on node state. A snapshot (`RunSnapshot`) is persisted after
  every transition (`persistence/RunRepository`, atomic `state.json`), so a run can be resumed.
- **Node** = id, type (STAGE or TASK), `dependsOn`, agent key, exit gate, retry budget, input hashes, output artifact
  ids, checkpoint commit. States: PENDING, READY, RUNNING, VALIDATING, PASSED, FAILED, RETRYING, ROLLED_BACK,
  WAITING_APPROVAL, SKIPPED, INVALIDATED. Run states: CREATED, RUNNING, WAITING_HUMAN, COMPLETED, SAFE_STOPPED, ABORTED.
- **Scheduler**: a node is READY when all dependencies are PASSED or SKIPPED. A node with several dependencies
  (release <- testing, docs, live-check) is the join/barrier.
- **Gate sandwich per node**: entry check -> assemble context -> agent -> parse/validate -> exit gate -> policy scan
  and risk classification -> (approval if needed) -> apply changes -> checkpoint commit.
- **Executors are pluggable** (`EngineConfig`): agents, gates, expanders, policies, checkpoint service and change
  applier are registered per engine. Engine tests use fakes for all of them.

### Dynamic re-planning (`Invalidator`)
Each node records the hash of every input artifact it consumed. If an upstream artifact changes (Gate 0 answer,
Gate 1 rejection feedback, `updateArtifact` on the requirement), the engine marks every downstream node whose input
hash changed as INVALIDATED and re-runs it. One mechanism covers clarification, design rejection and requirement
changes. In the ambiguous run, rejecting Gate 0 logged `INVALIDATED` on `requirements`, which re-ran with the
feedback and produced a new spec.

### Failure handling (`FailureRouter`, rules only, no LLM)
| Failure | Decision |
|---|---|
| compile error in `src/test` | retry the test engineer |
| compile error in `src/main` | route back to the owning `impl:` task |
| exception whose top project frame is in `src/main` | route back to the owning task |
| assertion failure, first time | retry the test engineer (treat as test defect) |
| same assertion again | route back to the owning task (implementation defect) |
| same code defect seen 3 times | SAFE_STOP early ("not converging") |
| `SPEC_GAP` | escalate to a human |
| precondition failure (e.g. unknown gate) | SAFE_STOP |
| anything else | retry with the exact errors as feedback |

Budgets: per node `retryBudget = 2`; per run 60 LLM calls and 8 back-edges. Exhausting any budget, a critical
policy violation or an operator abort ends in **safe-stop**: the workspace is rolled back, state is persisted,
the run ends SAFE_STOPPED.

Rollback: greenfield rolls back to the last good checkpoint. Brownfield rolls back to the **run-start** commit; the
failed state is first committed and tagged `failed-<runId>` in the workspace git so it can be inspected.

## 3. Governance (`governance/`)

- **Policies** (`PolicyEngine` + `Policies`): `Secrets` (credential regexes), `ForbiddenApis` (`Runtime.exec`,
  `System.exit`), `PomAllowlist`, `ChangeControl` (protected paths, deleting tests, sandbox), `AgentScope`.
  Violations carry a severity; HIGH fails the attempt, is audited as `POLICY_VIOLATION` and retried with the
  violation as feedback.
- **AgentPermissions**: the single table of what each agent may produce (`implementer`: `src/main/**` and `pom.xml`;
  `test-engineer`: `src/test/**`; `doc-writer`: README and docs; analysts/architect/reviewer: artifacts only).
  LLM calls have no tools; the only capability an agent has is the files and artifacts it returns.
- **RiskClassifier**: a pom change, a file deletion, or a MODIFY of an existing test is HIGH risk and requires a
  human `RISK_ACTION` approval before the change is applied.
- **HumanApprovalManager**: one queue per run, resolved from the console or REST, persisted and audited (channel,
  actor, decision, feedback). Kinds: GATE_0, GATE_1, GATE_2, RISK_ACTION, SPEC_GAP.
  Semantics: approve = proceed; reject + feedback = re-run the node with the feedback.
- **Baseline-test contract (brownfield)**: at run start `src/test` is snapshotted. `BaselineGuard` rejects removed or
  renamed baseline tests; a changed one must be declared in `supersededTests` with a reason, and each is audited
  as `BASELINE_TEST_SUPERSEDED`.

## 4. Observability (`observability/`)

- **Audit trail**: `audit.jsonl`, append-only, every event carries `prevHash` and `hash` (SHA-256 chain), so edits,
  deletions and reordering are detectable (`AuditLogger.verify`). Event types: RUN_STARTED / RESUMED / COMPLETED /
  SAFE_STOPPED / ABORTED, ENTRY_GATE, AGENT_START, AGENT_END, EXIT_GATE, NODE_PASSED, NODE_FAILED, POLICY_VIOLATION,
  RETRY, ROUTE_BACK, INVALIDATED, ROLLBACK, CHECKPOINT, HUMAN_APPROVAL, SAFE_STOP, BASELINE_TEST_SUPERSEDED.
- **MetricsService** derives every metric from the audit log: node success rate, retries, route-backs, rollbacks,
  invalidations, policy violations, human approvals, MTTR (first failure to recovery, per node), end-to-end
  latency, LLM calls, tokens, cost, cache hits. Written to `metrics-summary.json` when a run ends.
- **ReleaseGate** re-derives facts from the audit log (chain valid, no critical violations, every stage has a
  recorded pass, metrics present) instead of trusting the reviewer's text.

## 5. LLM layer (`llm/`)

`LlmClient.complete(LlmRequest)` has two backends chosen by Spring profile: `ClaudeCliAdapter` (default, `claude -p`
from a neutral temp cwd with tools, settings, MCP and skills disabled; system prompt passed with
`--system-prompt-file`) and `SpringAiAnthropicClient` (profile `anthropic`, API key). `CachingLlmClient` wraps the
backend with `record | replay | off` (key = hash of model + system + user), and `FakeLlmClient` serves tests.

## 6. Workspace and context

- `workspace/`: `Scaffolder` (deterministic baseline: pom, `Application`, `application.yml`, wrapper),
  `SandboxedFileWriter` (validates the whole batch; rejects absolute, `..`, drive and symlink escapes),
  `WorkspaceGit` (`--git-dir=.orchestrator/workspace.git --work-tree=url-shortener/`, so rollback can only touch the
  generated app), `MavenRunner` (timeout, output cap), `SurefireParser` (Surefire XML and javac errors ->
  `FailureInfo`).
- `context/`: `CodeIndexer` (JavaParser: classes, constructors, non-private methods, annotations, endpoints; regex
  fallback) and `ContextAssembler` (sections under one character budget). Brownfield agents receive the index
  summary plus full text only for files they target.

## 7. Interfaces and layout

REST (`api/`): `POST /api/runs`, `GET /api/runs[/{id}]`, `resume`, `abort`, `PUT /{id}/requirement`, `metrics`,
`audit`, `audit/verify`, `artifacts/{aid}`, `approvals`, `POST /{id}/approvals/{aid}`. Console (`cli/`): same flow
with `a` / `r <feedback>` prompts. Both go through `service/RunService`.

```
orchestrator/        engine, governance, agents, llm, workspace, context, observability, persistence, api, cli
url-shortener/       generated target app (tracked as a deliverable)
.orchestrator/       gitignored: workspace.git, runs/<id>/{state.json,artifacts/,audit.jsonl}, llm-cache/
docs/runs/           exported audit, metrics, state and artifacts of the three scenario runs
```
