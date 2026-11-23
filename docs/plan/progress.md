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

## P4 — agents, gates, permissions, scripted greenfield dry-run (DONE 2026-11-22)

**Verified:** `orchestrator\mvnw.cmd test` -> exit 0, 84 tests in the new package (2 opt-in live tests skipped). New: `RequirementsAgentTest`, `GatesAndHelpersTest`, `AgentPermissionsTest`, `GreenfieldDryRunTest`. No real LLM or Maven process was used anywhere in P4.

**Built (`agents/`, design per plan §3a):**
- `base/`: `SdlcAgent` (key + run), `AbstractLlmAgent<R>` (prompt -> LlmClient -> JsonResponseParser -> record; one repair attempt with the parse/validate error fed back; hooks `validate(ctx,r)`, `finish`, `onGiveUp`), `AgentExecutor` (never lets null/exception escape), `AgentRegistrar` (`@Order(0)`; wires agents, `GateBean`s, `ExpanderBean`s and the AgentScope policy into every engine), `PromptLoader` (fails fast), `OwnerResolver` (file/class -> `impl:<task>` from the task graph), `FileEntry`, `Json`.
- One package per agent: `requirements/` (+`RequirementsGate`, Gate 0 on BLOCKING ambiguity), `codebase/` (+`ImpactGate`), `architecture/` (+`ArchitectureGate`: schema, acyclic, AC coverage both ways, src/main only, project package, layer naming rules; `TaskGraphExpander`), `implementation/` (`ImplementerAgent` limited to its task's targetPaths; `CompileGateExecutor`), `testing/` (`TestEngineerAgent` + `TestsGate`), `docs/` (`DocWriterAgent` with template fallback + `DocsGate`), `release/` (`ReleaseReviewerAgent`, `ReleaseGate`, `ReleaseFacts`).
- Prompts + output JSON shapes in `resources/prompts/<key>.md` (8 files; the shapes live in the prompts, there are no separate schema files).
- `governance/AgentPermissions` + `Policies.AgentScope`: per-agent write globs, may-delete, allowed artifact ids; HIGH severity (attempt fails, audited, retried with feedback). Unknown agent = may do nothing. `PolicyInput` now carries the agent key (old 3-arg constructor kept); `PolicyEngine.with(policy)`.

**Deviations / decisions:**
- `SdlcAgent` is not generic (so `compile-gate` can implement it); the typed record lives in `AbstractLlmAgent<R>`.
- The engine applies file changes only after exit gates, so `TestEngineerAgent` runs the tests in a scratch copy of the workspace (`MavenRunner.withWorkspace(dir)`), builds the `TestReport` from real Surefire results and AC ids found in `@DisplayName("AC-n ...")`, and returns the test files as changes. `TestsGate` only judges that report. Same pattern: executors compute facts, gates re-derive them (`ReleaseGate` recomputes from the audit log).
- LLM output records are separate from measured artifacts (`TestSuite` -> `TestReport`, `DocSet` -> `DocReport`, `ReleaseReview` -> `ReleaseReadinessReport`).
- Parse/schema failures use `FailureKind.TRANSIENT` (no SCHEMA/PARSE kinds exist).
- Release stage also stores `release-report.md` (for the Gate 2 reviewer).
- Tests/fixtures: `dryrun/GreenfieldFixtures` (scripted replies), `dryrun/FakeMaven` (no process; one injected javac error at wave 1; turns `@DisplayName`s into Surefire cases). The dry run exercises: non-JSON architect reply (repair), T3 writing a file it doesn't own (rejected), Gate 1 reject + replan with feedback, wave-1 compile error routed back to `impl:T1` (route-back + MTTR recorded), parallel testing || docs, join, Gate 2, hash-chain verifies, files land in the workspace.

**Bug found and fixed (P3 code):** `CodeIndexer` used the static JavaParser, which is not thread-safe; testing and docs run in parallel, so one of them intermittently got the regex fallback (no endpoints) and `docs-valid` failed. Now one `JavaParser` per call.

**Open issues / for P5:**
- Fixture sources verified under real Maven: `SCAFFOLD_LIVE=1 .\mvnw.cmd test -Dtest=FixtureBuildLiveTest` (scaffold + fixtures, `mvnw test`: compiles, 4 tests pass, ~27 s). The scripted dry run itself still uses FakeMaven.
- Brownfield (ImpactGate, CodebaseAnalyst) and Gate 0 are unit-tested only, not yet run end to end (P6).
- `CodeIndexer` still skips private methods/fields; `DocsGate` matches endpoints by `VERB /path` text, so a README that lists an endpoint in prose without that form counts as missing.
- Ideas from a suggested scenario set that we may adopt in P6/P7: a scripted Gate 0 answer file for the ambiguous scenario; a canned Gate 1 rejection line for brownfield. Scenario texts stay as `.md` (cheaper than the richer 6-requirement prompt).
- Nothing committed yet (user has not asked).

**Next (P5):** live Greenfield with the real CLI (pin a stronger model than the haiku default), record to the cache, export to `docs/runs/greenfield/`. Run the scaffold build check first (`SCAFFOLD_LIVE=1`).

## Pre-P5 (2026-11-22)
- Default backend model is now `sonnet` (`application.yml` `orchestrator.llm.model`, and the `LlmProperties` fallback). The `anthropic` profile still says `claude-haiku-5-5` (not used; no API key). Live smoke test (`CLAUDE_CLI_LIVE=1`) now uses `sonnet` and passed: `claude -p` is logged in and the alias resolves.
- P5 uses `claude -p` (subscription), not the Anthropic API. Run with cache-mode `record`.

## Pre-P5 live trial: minimal requirement (2026-11-22)
Ran `docs/runs/minimal-requirement.md` live (sonnet, `claude -p`, console, approvals auto-"a"): run `run-20261123-042942-c6a8` ended **SAFE_STOPPED** at `testing` after 2 retries. 17 nodes passed, 1 route-back (wave:2 compile error -> impl:T5, recovered, MTTR ~19 s), 16 LLM calls, $0.87, ~5.5 min. Audit chain and metrics worked.
- **Cause (strong evidence, not yet fixed):** `tests-valid` says no passing test for AC-1..AC-14. Generated tests use `@DisplayName("AC-n ...")` on methods named `ac1_...`, but real Surefire XML `name` is the method name, so `TestEngineerAgent.buildReport`/`mentions` finds no AC id. FakeMaven (dry run) writes display names into the XML, which hid this. Fix options: make `SurefireParser` also match `acN_` method names, or configure surefire to report display names; add a real-Maven check.
- Spec had 14 ACs for a 3-sentence requirement (over-specified); consider prompting for fewer, coarser ACs.
- `url-shortener/` shows as untracked in git; decide whether to ignore it.

### Fixes after the live trial (2026-11-22) — 87 tests green (3 live skipped)
- **Layout:** mandatory packages `controller/ service/ dao/ provider/ dto/ validator/ util/` (entities + repositories in `dao/`, exceptions in `service/`, @RestControllerAdvice in `controller/`). Encoded in `prompts/architect.md` + `implementer.md` and enforced by `ArchitectureGate` (`LAYERS`, suffix rules, no files in package root). This had never been in the prompts/plan before (old layers were api/repository/model/client/config).
- **Duplicate entry point/config:** architect prompt says the scaffold owns `Application.java` and `application.yml`; gate rejects root-package Java files and `application.properties`.
- **AC matching:** `TestEngineerAgent.mentions` accepts `AC-1` (display name) and `ac1_` (method name, which is what Surefire reports); test-engineer prompt requires both.
- **AC count:** requirements prompt now asks for coarse, few ACs (4-8 for a small service).
- Dry-run fixtures/tests moved to the new layout. Not yet re-run live. Before the next live run delete `url-shortener/` and `.orchestrator/` (stale workspace and run state).

### Rerun + live-check (2026-11-23) — 92 tests green (3 live skipped)
- **Rerun result:** `run-20261123-052155-cb45` COMPLETED, release READY: 6 ACs, 21 tests passed, 13 LLM calls, ~$0.58, 1 recovered route-back (wave:1 compile error -> impl:T6), 0 retries. The AC-matching fix works on real Surefire output.
- **Gotcha:** `--orchestrator.cli.requirement-file=` alone gives scenario `custom`, which is NOT greenfield, so nothing is scaffolded (no `mvnw.cmd`) and wave:0 fails. Always pass `--orchestrator.cli.scenario=greenfield` together with the file. Possible hardening: treat a run as greenfield when the workspace has no `pom.xml`.
- **Manual live test of the generated app** (curl on port 8099): shorten 201, redirect 302, unknown 404, blank/missing/ftp/javascript/malformed all 400, codes survive a restart. Gap: `http://169.254.169.254/...` is accepted (201); no private/link-local host filtering (not in the spec).
- **New advisory `live-check` stage** (`agents/livecheck/`): non-LLM, depends on `implementation`, parallel with testing/docs; `release` depends on it and the reviewer sees `live-report`. `ProcessAppRunner` packages with the workspace wrapper, boots the jar on a free port from a temp dir, `LiveProbes` runs 9 HTTP probes (8 required + 1 hardening), process killed afterwards. Never fails the run; boot failure yields `started=false`. `LiveAppRunner` is the seam (dry run injects a failing runner). Not yet exercised against a real generated app inside a run.
- **To revisit:** make it blocking (return a failure from `LiveCheckExecutor` + give the stage an exit gate) once it is stable across live runs. Also known: release report shows run outcome IN_PROGRESS (report is built before the run closes) and the doc extractor names the redirect endpoint `GET /{}`.
- **Next (P5):** delete nothing needed (workspace and `.orchestrator/` were cleared). Run live greenfield with `scenario=greenfield`, then export to `docs/runs/`.

### Live run 1 safe-stop + fixes (2026-11-23)
- **Run `run-20261123-062138-bb15` SAFE_STOPPED** ("back-edge budget exhausted (8)"): `wave:1` hit the same COMPILE_MAIN error 8 times, routed back to `impl:T5` each time. T5 alternated `new ShortUrl(String,String,Instant)` and `new ShortUrl()`; the entity (another task's file) only had `ShortUrl(String,String)` + a protected no-arg constructor.
- **Root causes:** (1) `CodeIndexer` indexed methods only, so constructors were invisible to the implementer; (2) `SurefireParser.parseCompileErrors` kept only javac's first line per error, dropping `required:/found:/reason:/symbol:/location:`, and each error was reported twice (Maven prints it in two blocks); (3) nothing stopped identical repeated code failures, so the whole back-edge budget was burned.
- **Fixes:** `CodeIndex.ClassInfo` has a `constructors` list (non-private ctors; canonical form for records), rendered as `constructor ...` in the prompt summary. `parseCompileErrors` keeps indented javac detail lines and de-duplicates. `FailureRouter.route(node, f, timesSeen)`: COMPILE_MAIN/EXCEPTION_MAIN seen `MAX_SAME_CODE_FAILURE` (3) times -> SAFE_STOP early ("not converging"); the boolean overload still exists. `WorkflowEngine.handleFailure` appends a "this exact error occurred N times, do not repeat it, use the signatures in the EXISTING CODE index" note to the feedback from the 2nd occurrence on.
- Tests added: `ContextTest.indexesNonPrivateConstructorsAndRecordComponents`, `SurefireParserTest.keepsJavacDetailLinesAndDropsDuplicates`, `GraphAndRulesTest.repeatedCodeDefectStopsEarlyInsteadOfBurningTheBudget`. `mvnw test`: exit 0, no failures or errors.
- **Next (P5):** delete `url-shortener/` and `.orchestrator/` (stale workspace + run state), rerun live greenfield (with `scenario=greenfield`), then export to `docs/runs/greenfield/`. Not yet re-run live after these fixes.

## P5 DONE (2026-11-23)
- **Live greenfield `run-20261123-063431-d43d` COMPLETED**, release READY: 18 nodes passed, 0 failed, 0 retries/route-backs/rollbacks, 2 human approvals, 14 LLM calls, 67,146 in / 29,489 out tokens, $0.557, e2e 202.6 s. 6 ACs, 25 tests passed, live-check: all required probes passed.
- **Exported** to `docs/runs/greenfield/` (audit.jsonl, metrics-summary.json, state.json, artifacts/, requirement-input.md). There is no export command; it was a manual copy from `.orchestrator/runs/<id>/`. The orchestrator-side hash-chain verify was not re-run on the final 71-event file (release report verified 66 events before the run closed).
- **Known / carry to P7:** live-check hardening probe failed (private/link-local URL accepted, 201; not in spec); release report shows outcome IN_PROGRESS and no e2e latency (report built before run closes); doc extractor renders redirect as `GET /{}`. `url-shortener/` is untracked (decide: commit as the generated artifact or ignore). `.orchestrator/llm-cache` (gitignored) holds the recorded replies P7 needs for replay mode.
- **Next (P6):** Brownfield + Ambiguous scenarios.
