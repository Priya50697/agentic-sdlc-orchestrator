# 02 - Agent Specifications

Seven LLM agents plus two non-LLM executors. Each agent is a thin class: **context in -> prompt template ->
`LlmClient` -> `JsonResponseParser` -> typed record out**. Decisions never live in prompts: every stage has a
deterministic exit gate, and the engine applies policies and approvals around it.

Code lives in `orchestrator/src/main/java/com/sdlcagent/orchestrator/agents/<agent>/` (agent, result record, gate).
Prompts and the output JSON shape are in `orchestrator/src/main/resources/prompts/<key>.md`; write scope per agent
is in `governance/AgentPermissions`.

## Shared base (`agents/base/`)

- `SdlcAgent`: `key()` + `run(NodeContext)`. `AbstractLlmAgent<R>`: loads the prompt (`PromptLoader`, fails fast if
  missing), calls the LLM, parses to record `R`, runs `validate`. On a parse or validation failure it makes **one
  repair attempt** with the error fed back, then gives up with a `FailureInfo` (never a null or exception).
- `AgentExecutor` adapts an agent to the engine's `NodeExecutor` (maps usage, cost, failures).
  `AgentRegistrar` collects all agent, gate and expander beans into every engine, plus the `AgentScope` policy.
- Self-registering: adding an agent is adding a package; no central edits.

## Agent table

| Key / stage | Reads | Produces (artifact) | Exit gate (deterministic) | May write |
|---|---|---|---|---|
| `requirements` | raw requirement (+ Gate 0 feedback) | `spec` (stories, numbered ACs, ambiguities with severity and proposed default, non-functionals) | `requirements-valid` | artifact `spec` only |
| `codebase-analyst` (brownfield only) | `spec`, CodeIndex | `impact` (affected files/classes, new components, risks) | `impact-valid` | artifact `impact` |
| `architect` | `spec`, `impact`, CodeIndex | `architecture-plan` + `task-graph` | `architecture-valid` (+ Gate 1) | artifacts only |
| `implementer` (one call per task) | task, plan, CodeIndex, full text of its target files | files (CREATE / MODIFY / DELETE) | `wave:<n>` compile gate, policies | `src/main/**`, `pom.xml`, limited to the task's `targetPaths` |
| `compile-gate` (non-LLM) | workspace | pass or `FailureInfo` with owner task | n/a | nothing |
| `test-engineer` | `spec`, `plan`, CodeIndex, baseline test inventory | test files + `test-report` (+ `superseded-tests`) | `tests-valid` | `src/test/**` |
| `doc-writer` | `spec`, `plan`, CodeIndex | `README.md` + `doc-report` | `docs-valid` | `README.md`, `docs/**/*.md` |
| `live-check` (non-LLM, advisory) | built jar | `live-report` | none; never fails the run | artifact only |
| `release-reviewer` | `test-report`, `doc-report`, `live-report`, release facts | `release-report` (+ `.md`) | `release-valid` (+ Gate 2) | artifacts only |

## Stage details

**Requirements.** Splits the requirement into stories S1.. and coarse acceptance criteria AC-1.. (normally 4-8 for a
small service; the prompt forbids inventing limits the requirement does not state). Every ambiguity carries a
`proposedDefault`; it is BLOCKING only if two readings lead to different behaviour or architecture.
*Gate `requirements-valid`*: at least one story and one AC; AC ids unique, each AC at least 15 characters and
tied to a known story; every BLOCKING ambiguity has a default. If any BLOCKING ambiguity remains the gate returns
`needsApproval(GATE_0)` listing the questions and defaults. Approve accepts the defaults; reject + feedback re-runs
the agent with the human's answer treated as authoritative (which changes the `spec` hash and invalidates
downstream nodes).

**Codebase analyst.** Receives the CodeIndex summary, not the sources. *Gate `impact-valid`*: every named path and
class must exist in the index (or be declared as a new component); an impact that names nothing is rejected.
Skipped (state SKIPPED) in greenfield.

**Architect.** Emits components (each with a layer), endpoints, data model and a task list with `dependsOn`,
`targetPaths` and `acceptanceCriteria`. *Gate `architecture-valid`*: JSON shape valid; task graph acyclic; every
task maps to at least one known AC and has target paths; every AC is covered by some task; paths are safe,
under `src/main`, inside the project package; the scaffold owns `Application.java` and `application.yml`; layer
naming rules (`*Controller`/`*ControllerAdvice` in `controller/`, `*Repository` in `dao/`, `*Service` in
`service/`, `*Validator` in `validator/`, `*Provider` in `provider/`, `*Request`/`*Response`/`*Dto` in `dto/`; no
files in the package root). Followed by **Gate 1** (human approval of spec + design + task graph).

**Implementer.** Runs per task, wave by wave. It can only return files inside its task's `targetPaths`; anything
else is an `AgentScope` violation (attempt fails and is retried with the violation). The context carries the
constructor and method signatures of code written by earlier tasks, which prevents mismatched calls across files.
A compile failure after the wave routes back to the owning task via `OwnerResolver` (file/class -> `impl:<task>`).

**Test engineer.** Runs the tests itself in a **scratch copy** of the workspace (the engine only applies changes
after exit gates), builds the `TestReport` from real Surefire results, and returns the test files as changes.
Each AC must be covered by a passing test, identified by `@DisplayName("AC-n ...")` or a method name `acN_...`.
Brownfield: output is `files` + exact-match `edits` + `supersededTests`; `BaselineGuard` enforces the baseline
contract inside the agent before any Maven run. *Gate `tests-valid`*: suite green (no failures or errors) and every
AC has at least one passing test. Failures are classified by `SurefireParser.classify` and routed by the rules in
doc 01.

**Doc writer.** Produces `README.md` (template fallback exists). *Gate `docs-valid`*: required sections present, and
the set of documented `VERB /path` endpoints equals the set of real `@*Mapping` endpoints from the CodeIndex (none
missing, none invented).

**Live check.** Packages the app with the workspace wrapper, boots the jar on a free port from a temp dir, runs 9
HTTP probes (8 required, 1 hardening), kills the process. Advisory: a boot failure yields `started=false`; it never
fails the run. The reviewer sees the result as `live-report`.

**Release reviewer.** Returns a verdict (`READY | NOT_READY`), summary, risks and follow-ups. *Gate `release-valid`*
recomputes the facts from the audit log: chain verifies, no critical violations, every stage has a recorded pass,
metrics exist, and the verdict is READY. Followed by **Gate 2** (human release sign-off).

## Cross-cutting rules

- **No tools for the LLM.** `claude -p` runs with tools, settings, MCP and skills disabled. An agent can only emit
  text; the orchestrator validates it and applies changes through `SandboxedFileWriter`.
- **Executors compute facts, gates re-derive them** (tests report, release facts), so an agent cannot talk its way
  past a gate.
- **Parse or schema problems** are `FailureKind.TRANSIENT` failures handled by the one repair attempt, then the
  node's retry budget.
- **Tests per agent package**: happy path and malformed-output path with `FakeLlmClient`, plus the scripted
  greenfield dry run (`dryrun/GreenfieldDryRunTest`) over the whole graph.
