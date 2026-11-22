# Progress / Phase Handoff Log

Newest phase last. Each entry: built, deviations from the plan, open issues, next steps.

## P1 — LLM layer, observability, persistence (DONE 2026-11-22)

**Verified:** `orchestrator\mvnw.cmd test` -> BUILD SUCCESS, 14 tests (1 opt-in live test skipped by default; it passed when run with `CLAUDE_CLI_LIVE=1`).

**Built (in checkpoints, each built clean before the next):**
1. `git init` (branch `main`), `.gitignore`, orchestrator pom (Boot 3.5.6, Java 21, Spring AI BOM 1.0.3), `mvnw` generated with IntelliJ's bundled Maven 3, context-loads test.
2. `llm/` core: LlmClient/Request/Response/Exception, FakeLlmClient, CachingLlmClient (sha256 of model+system+user, one JSON file per entry, record/replay/off), JsonResponseParser (fences + prose tolerant).
3. `ClaudeCliAdapter`: flags verified against the installed CLI (see below); envelope parser tested on a real reply.
4. `observability/`: AuditLogger (hash chain, resumes an existing file, `verify` detects tamper/deletion), MetricsService (success rate, retries, route-backs, rollbacks, invalidations, MTTR, E2E latency, tokens/cost).
5. `persistence/`: RunRepository (atomic snapshot, generic over snapshot type), RunPaths.
6. `SpringAiAnthropicClient` + LlmConfig/LlmProperties + application.yml; wiring tests for default and `anthropic` profiles.

**Verified `claude -p` invocation** (claude 2.1.330): `claude -p --output-format json --tools "" --no-session-persistence --setting-sources "" --strict-mcp-config --disable-slash-commands [--model X] [--system-prompt S]`, prompt on stdin, cwd = temp dir. JSON envelope fields used: `result`, `is_error`, `total_cost_usd`, `usage.{input_tokens,cache_creation_input_tokens,cache_read_input_tokens,output_tokens}`, `duration_ms`, `modelUsage` (key = resolved model id). `--model haiku` resolves to `claude-haiku-5-5`. A tiny call costs ~$0.0001 (mostly a ~670-token cache-creation prefix).

**Deviations / decisions:**
- Added `NODE_PASSED`, `NODE_FAILED`, `RUN_RESUMED` audit event types (MetricsService needs them for success rate and MTTR). The engine in P2 must emit them.
- `spring.ai.model.chat=none` in default config so the Spring AI starter is inert (no API key needed) unless profile `anthropic`.
- Spring AI usage gives tokens but no cost, so `costUsd` is 0 on the anthropic path.
- Cache mode defaults to `record`; set `orchestrator.llm.cache-mode=replay` for fixtures-only runs.
- `ClaudeCliAdapter` default model alias is `haiku` (cheap for dev); pin a stronger model for real scenario runs.
- Nothing is committed yet (`git init` done, no commits). The user did not ask for a commit.

**Open issues:**
- Mockito self-attach warnings on JDK 21 in test output are harmless; add the agent flag later if noisy.
- `SpringAiAnthropicClient` is only wiring-tested (no live API call, no key available in dev).
- Timeout is not enforced per call on the Spring AI path.

**Next (P2):** `engine/` (Node, StageGraph, TaskGraph, Scheduler, Invalidator, FailureRouter) and `governance/` (Gates, Policy, PolicyEngine, RiskClassifier, HumanApprovalManager), with unit tests using FakeLlmClient: scheduling, join, retry, route-back, invalidation, rollback, safe-stop, approvals. Use `RunRepository` for snapshots and `AuditLogger` for every transition.

## P2 — engine + governance (DONE 2026-11-22)

**Verified:** `orchestrator\mvnw.cmd test` -> exit 0, 38 tests (1 opt-in live test skipped). New: `WorkflowEngineTest` (17 scenarios, all FakeLlmClient), `GraphAndRulesTest`.

**Built:**
- `engine/`: `WorkflowEngine` (single-threaded coordinator; attempts run on virtual threads and report via a queue), `Node`/`RunSnapshot` (Jackson-friendly, persisted via RunRepository after every transition), `StageGraph.standard(greenfield)`, `TaskGraph` (parse, validate, waves, `toExpansion`), `Scheduler`, `Invalidator` (hash-driven), `FailureRouter` (rules only), `EngineConfig`, `ArtifactStore`, `CheckpointService`/`ChangeApplier` interfaces (NOOP defaults; P3 supplies git/sandbox impls).
- `governance/`: `Gate`/`GateResult`, `PolicyEngine` + 4 built-in policies, `RiskClassifier`, `HumanApprovalManager`.

**How it works (for P3/P4):**
- `run()` returns when the run is terminal OR idle in `WAITING_HUMAN`. Resolve approvals via `engine.approvals().resolve(...)`, then call `run()` again (console/REST loop does this). `resume(runId,...)` reloads state.json.
- Executors are `NodeExecutor` keyed by `Node.agent`; they return `NodeResult(artifacts, changes, usage, failure)` and never touch the workspace. The engine runs policies, risk classification and approvals, then calls `ChangeApplier`, then `CheckpointService.checkpoint`.
- Artifact ids are constants in `StageGraph` (`requirement`, `spec`, `impact`, `architecture-plan`, `task-graph`, `test-report`, `doc-report`, `release-report`). The requirement is stored as artifact `requirement` at `start()`; `updateArtifact` changes it and the Invalidator re-plans.
- Gate names used by the standard graph (must be registered in P4): `requirements-valid, impact-valid, architecture-valid, tests-valid, docs-valid, release-valid`. Unknown gate = PRECONDITION failure = safe-stop. Gate 0 = the requirements exit gate returning `GateResult.needsApproval(GATE_0, ...)`.
- Approval semantics: GATE_x approve = proceed, reject + feedback = rerun node with feedback; RISK_ACTION reject = attempt fails (retry); SPEC_GAP approve + feedback = rerun with the answer, reject = safe-stop.
- Failure routing: owner node id comes from `FailureInfo.ownerNodeId` (e.g. `impl:T2`); route-back invalidates the path target..failing node. Task nodes are `impl:<taskId>`, wave compile gates `wave:<n>` (agent `compile-gate`), join node `implementation`. P4's TestEngineer/MavenRunner must fill `FailureInfo` (kind, signature = test id, owner) from Surefire/javac output.
- Budgets (`EngineConfig.budget`): default 60 LLM calls, 8 back-edges; per node `retryBudget=2`.

**Deviations / decisions:**
- `MetricsService` now sums the `llmCalls` key of AGENT_END when present (non-LLM nodes like compile gates log 0); old behavior kept when absent.
- Added `AuditLogger.data(...)` (null-tolerant map helper) and `ApprovalRequest.Status.CANCELLED` (pending approval withdrawn when its node is invalidated).
- `RUN_ABORTED` is only used for an unexpected engine exception; operator abort = rollback + SAFE_STOPPED.
- `FileChange` lives in `governance/` (policy input); engine and governance reference each other (same module, acceptable).
- Safe-stop logs ROLLBACK even when no checkpoint exists yet (`to=null`).

**Open issues:**
- No per-node wall-clock timeout in the engine (relies on LlmClient timeout and, in P3, MavenRunner timeout). In-flight workers are not cancelled on abort; their results are discarded.
- Policy regex lists are minimal (secrets, exec/System.exit, pom allowlist, protected paths); SSRF patterns deferred to P4 with the generated-app checks.
- Nothing committed yet (user has not asked).

**Next (P3):** `workspace/` (Scaffolder, WorkspaceGit implementing CheckpointService, SandboxedFileWriter implementing ChangeApplier, MavenRunner + SurefireParser), `context/` (ContextAssembler, CodeIndexer), then REST (RunController/ApprovalController) and ConsoleRunner on top of `WorkflowEngine`.

## P3 — workspace, context, REST + console (DONE 2026-11-22)

**Verified:** `orchestrator\mvnw.cmd test` -> exit 0, 61 tests (1 opt-in live CLI test skipped). `SCAFFOLD_LIVE=1 ... -Dtest=ScaffoldBuildLiveTest` passed: the scaffolded app compiles with its own wrapper. New tests: `WorkspaceTest`, `SurefireParserTest`, `ContextTest`, `RunApiTest` (REST + console + git checkpoints, fake executors, no LLM).

**Built:**
- `workspace/`: `SandboxedFileWriter` (validates the whole batch before writing; rejects absolute, `..`, drive paths, symlink escapes), `WorkspaceGit` (git dir outside the workspace via `--git-dir/--work-tree`; `info/exclude` ignores `target/`; checkpoint returns HEAD when nothing changed; rollback = `reset --hard` + `clean -fd`), `Scaffolder` (pom with Web/JPA/H2/Validation/Test, wrapper copied from orchestrator, `Application`, `application.yml`; idempotent; initial checkpoint), `MavenRunner` (`mvnw.cmd` via `cmd /c` on Windows, timeout kills the process tree, output capped at 200k chars), `SurefireParser` (Surefire XML, javac error lines, and `classify(...)` -> `FailureInfo` using the plan's rules; owner node comes from a caller-supplied `Function<String,String>`).
- `context/`: `CodeIndexer` (JavaParser 3.26.4 added to pom; classes, non-private method signatures, class annotations, endpoints from `@*Mapping`; regex fallback marks `parsed=false`), `CodeIndex` (`hasFile/hasClass/endpoints/summary(maxChars)`), `ContextAssembler` (sections under one char budget, largest trimmed first).
- `service/RunService`: create (scaffold for greenfield, else a baseline checkpoint) / run / runAsync / abort / updateRequirement / resolve, plus read-only views. Writes `metrics-summary.json` when a run ends. `EngineConfigCustomizer` beans are applied to every new engine after the workspace wiring.
- `api/`: `POST /api/runs`, `GET /api/runs[/{id}]`, `POST /{id}/resume|abort`, `PUT /{id}/requirement`, `GET /{id}/metrics|audit|audit/verify|artifacts/{aid}|approvals`, `POST /{id}/approvals/{aid}` (resolves and resumes the run).
- `cli/`: `ConsoleRunner` (activated by `--orchestrator.cli.scenario=` / `requirement-file=` / `resume=`), `ConsoleSession` (approve `a`, reject `r <feedback>`; end of input = safe-stop), `Scenarios` (3 requirement texts in `resources/scenarios/`).

**How P4 plugs in:** expose a bean `EngineConfigCustomizer` that registers executors (keys: requirements, codebase-analyst, architect, implementer, compile-gate, test-engineer, doc-writer, release-reviewer), the six gates, and the `task-graph` expander (see `RunApiTest.FakeAgents` for the minimal shape). `compile-gate` and `test-engineer` should use `MavenRunner` + `SurefireParser.classify`.

**Deviations / decisions:**
- State root default is now `../.orchestrator` (repo root, as in the plan) and workspace dir `../url-shortener`; both are relative to `orchestrator/`, the cwd for `mvnw`. Override with `orchestrator.llm.state-root` / `orchestrator.workspace.dir`.
- `RunRepository` now uses a process-wide lock for save/load. The engine and the read-only API each hold their own instance, and on Windows a reader holding `state.json` makes the replacing move fail with AccessDenied (hit in `RunApiTest`).
- REST status/metrics/audit read the persisted snapshot only (never touch a live engine's mutable state, never resume as a side effect). Consequence: right after an approval the persisted state can briefly still say WAITING_HUMAN; clients should look at `pendingApprovals`.
- `ArtifactStore` stayed in `engine/` (plan listed it under `context/`).
- No initial checkpoint is stored in the snapshot: rollback target is null until the first node with file changes passes, which is fine because nothing is written before that.

**Open issues:**
- No auth on REST (local demo tool). In-flight workers are still not cancelled on abort (P2 note).
- `CodeIndexer` skips private methods and does not index fields or nested types.
- Nothing committed yet (user has not asked).

**Next (P4):** 7 agents + prompt templates (`resources/prompts/*.md`) + JSON schemas, gate implementations (`requirements-valid`, `impact-valid`, `architecture-valid`, `tests-valid`, `docs-valid`, `release-valid`), `EngineConfigCustomizer` wiring, task-graph expander; dry-run greenfield with record/replay.

## Package rename (pre-P4)
- Orchestrator package `com.example.orchestrator` -> `com.sdlcagent.orchestrator` (groupId `com.sdlcagent`); generated app package `com.example.shortener` -> `com.sdlcagent.urlshortener` (scaffold templates + Scaffolder). 62 tests green (2 live skipped).
- Pre-rename backup: `C:\Users\priya\OneDrive\Documents\New Work Prep\AI\Backup\agentic-sdlc-url-shortener-pre-rename`

## P4 pre-work: agent structure decided (2026-11-22)
- Reviewed redis-developer/multi-agent-app-spring-ai (1-orchestration). Adopted its modular per-agent package idea; design is in `docs/plan/00-foundational-plan.md` §3a (base/ package with `SdlcAgent<R>`, `AbstractLlmAgent<R>`, `AgentExecutor`, `AgentRegistrar`, `PromptLoader`; one sub-package per agent holding agent, record result, gate, prompt in `resources/prompts/`).
- Deliberately NOT adopted: Redis chat memory, LLM-driven `finishReason` control flow, inline prompts, fixed linear pipeline.
- P4 should build `agents/base/` first (with tests), then agents in stage order, then a greenfield dry-run via replay fixtures.
- Initial commit `c88a537` exists on main; git identity is set repo-locally.
