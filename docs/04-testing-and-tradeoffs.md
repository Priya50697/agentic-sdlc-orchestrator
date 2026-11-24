# 04 - Testing and Trade-offs

## 1. How the orchestrator is tested

124 `@Test` methods under `orchestrator/src/test`. **No unit test calls the real LLM or a real Maven process** (live
tests are opt-in via environment variables, see doc 03). The seams that make this possible: `LlmClient`
(`FakeLlmClient`, replay cache), `EngineConfig` (pluggable executors, gates, policies, checkpoint service, change
applier), and `MavenRunner` (`dryrun/FakeMaven`).

| Area | Tests | What is covered |
|---|---|---|
| Engine | `WorkflowEngineTest`, `GraphAndRulesTest` | scheduling, join, retry, route-back, invalidation, rollback (last good and run-start), safe-stop, approvals, budgets, "same defect 3 times stops early" |
| Governance | `AgentPermissionsTest`, policy cases in engine tests | per-agent write scope, HIGH-risk approvals, violations retried with feedback |
| LLM | `LlmCoreTest`, `ClaudeCliAdapterTest`, `AnthropicProfileWiringTest` | cache record/replay, JSON extraction from fences/prose, CLI envelope parsing, system prompt file, profile wiring |
| Observability / persistence | `ObservabilityTest`, `RunRepositoryTest` | hash chain verifies and detects tamper/deletion, metrics derivation, atomic snapshots |
| Workspace / context | `WorkspaceTest`, `SurefireParserTest`, `ContextTest` | sandbox escapes, git checkpoint/rollback, javac and Surefire parsing, code index (constructors, records, endpoints) |
| Agents | `RequirementsAgentTest`, `GatesAndHelpersTest`, baseline-test tests, `LiveCheckTest` | malformed output and repair, every gate's accept/reject rules, baseline guard, live probes |
| End to end | `GreenfieldDryRunTest`, `RunApiTest` | whole graph over scripted replies (see below), REST + console + git checkpoints |

The **greenfield dry run** exercises, with scripted LLM replies and no processes: a non-JSON architect reply
(repair), a task writing a file it does not own (rejected), a Gate 1 rejection with feedback and replan, a wave
compile error routed back to the owning task (route-back and MTTR recorded), parallel testing and docs with join,
Gate 2, hash-chain verification, files landing in the workspace. Fixture sources were separately verified under
real Maven (`FixtureBuildLiveTest`).

## 2. The three live runs

All three used `claude -p` with `sonnet`; exports are in `docs/runs/<scenario>/`.

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Run | `run-20261123-063431-d43d` | `run-20261124-142820-c6a0` | `run-20261124-143909-7885` |
| Outcome | COMPLETED | COMPLETED | COMPLETED |
| LLM calls | 14 | 17 | 14 |
| Cost | $0.56 | $1.07 | $0.89 |
| End-to-end | 203 s | 311 s | 241 s |
| Retries / route-backs | 0 / 0 | 1 / 0 | 0 / 0 |
| Invalidations | 0 | 0 | 1 |
| Human approvals | 2 | 3 | 4 |
| Cache hits | not recorded | 0 | 0 |

What each run demonstrates:

- **Greenfield**: scaffold, CodebaseAnalysis SKIPPED, task waves with compile gates, parallel testing || docs ||
  live-check, join, Gates 1 and 2. 6 ACs, 25 tests passed.
- **Brownfield**: CodeIndex-driven impact analysis, a **real failure and recovery**: the first testing attempt failed
  on the baseline test `ac6_noAnalyticsStored` (the new nullable `expiresAt` field broke its exact field list), the
  router retried the test engineer, and the second attempt changed that test under the baseline contract. The change
  raised a `RISK_ACTION` approval ("Existing test changed ... ac6") and was audited as `BASELINE_TEST_SUPERSEDED`.
  MTTR recorded: ~94.5 s.
- **Ambiguous**: "Make it safer against abuse and more reliable when external checks fail." Blocking ambiguities
  went to Gate 0 with proposed defaults; rejecting once with "also block private and internal URLs" logged
  `HUMAN_APPROVAL` (REJECT) then `INVALIDATED`, the requirements node re-ran with the feedback, and the second Gate 0
  was approved. Gate 0 appeared twice, giving 4 approvals in total.

## 3. Deviations from the original plan (stated plainly)

- **Greenfield used the minimal requirement**, not the richer bundled scenario (analytics + URL-safety provider).
  The generated app therefore has only `POST /shorten` and `GET /{code}`; there is no analytics endpoint.
- **Greenfield was not re-run after the system-prompt fix.** Until then `--system-prompt` was truncated on Windows
  by argument quoting, so every agent ran on a partial prompt (fixed by passing `--system-prompt-file`). The
  brownfield and ambiguous runs are the first to use full prompts. The greenfield export is kept as a record, not
  as proof of the final prompts. Its cache-hit count was not recorded (metric added later).
- **Brownfield did not demonstrate "Gate 1 reject with feedback" or a new-dependency approval.** Gate 1 was approved
  and the HIGH-risk approval came from editing an existing test. Replan-by-rejection is demonstrated in the
  ambiguous run (at Gate 0) and in the scripted dry run (at Gate 1).
- **The ambiguous run needed no retry or route-back**, so it records no MTTR. MTTR is demonstrated by the brownfield
  run (and the dry run).
- **Live-check is advisory**, not blocking. Its hardening probe (a private/link-local URL such as
  `http://169.254.169.254/...` is accepted with 201) failed on the greenfield and brownfield runs, where the specs
  did not ask for it. In the final ambiguous run all probes passed, including this one: the Gate 0 feedback
  "also block private and internal URLs" made it a requirement, and the generated app now rejects those hosts.
- **Exports are partial copies**: `baseline-tests/` is left out, and there is no export command (manual copy).
  The hash chain was verified by the release gate before each run closed; the exported files were not re-verified
  afterwards.

## 4. Design trade-offs

| Decision | Why | Cost |
|---|---|---|
| Rules-only `FailureRouter`, policies and gates in code | deterministic, testable without an LLM, auditable | cannot recognise a novel failure shape; misroutes are fixed by changing rules or ownership mapping |
| Single-threaded coordinator, virtual-thread workers | no races on node state; easy to reason about and persist | one slow state transition blocks the loop; in-flight workers are not cancelled on abort (results are discarded) |
| Artifact-hash invalidation | one mechanism for Gate 0 answers, Gate 1 feedback and requirement edits | coarse: any hash change re-runs the whole downstream path |
| Executors compute facts, gates re-derive them | an agent cannot claim green tests or a clean audit | duplicated computation (e.g. release facts) |
| LLM has no tools; orchestrator applies sandboxed changes | write scope enforced per agent, whole batch validated before writing | agents cannot explore; they see a signature index, not the whole codebase |
| Signature-index context for brownfield | keeps prompts small and cheap | the test engineer once saw only signatures of existing tests and re-emitted whole files; fixed by `edits` + pulling the full text of tests named in feedback |
| Workspace git via `--git-dir` outside the workspace | rollback can only touch `url-shortener/`; no nested repo | custom git invocation; tags `failed-<runId>` live in `.orchestrator/workspace.git` |
| `claude -p` backend, record/replay cache | zero extra cost, reproducible reruns | CLI startup per call; replay hits only when model + system + user are identical, so prompt edits invalidate the cache |
| Mandatory package layout (`controller/ service/ dao/ provider/ dto/ validator/ util/`) enforced by `ArchitectureGate` | consistent generated code, mechanical check | opinionated; not configurable per run |

## 5. Bugs the live runs found (and what they changed)

| Found by | Problem | Fix |
|---|---|---|
| First live trial | Surefire reports method names, so `@DisplayName("AC-n")` was invisible and `tests-valid` failed; FakeMaven had hidden it | accept both `AC-n` and `acN_` forms; real-Maven fixture check |
| Greenfield retry | 8 identical compile errors burned the back-edge budget; constructors were not indexed and javac detail lines were dropped | index constructors, keep javac detail lines, early safe-stop after 3 identical code failures |
| Parallel stages | JavaParser's static parser is not thread-safe; docs stage intermittently got the regex fallback | one parser per call |
| Brownfield attempts 1-3 | baseline test could not pass the new spec; no owner for the failure; test engineer rewrote 25k-token files | baseline-test contract, `edits`, owner routing by AC (never for baseline tests), rollback to run start |
| Brownfield attempt 4 | `supersededTests` came back in a different shape every time | root cause was the truncated system prompt; fixed with `--system-prompt-file`; parsing also made tolerant |

## 6. Known limitations / next steps

- No authentication on REST; local demo tool only.
- No per-node wall-clock timeout in the engine (relies on LLM and Maven timeouts).
- `CodeIndexer` skips private methods, fields and nested types; `DocsGate` matches endpoints by `VERB /path` text,
  so a README that mentions an endpoint only in prose counts as missing.
- Release report is built before the run closes, so its facts are a pre-Gate-2 snapshot (outcome IN_PROGRESS,
  fewer approvals than `metrics-summary.json`); `metrics-summary.json` is the authoritative figure.
- Making live-check blocking, SSRF hardening as a default policy for generated code, and a real export command are
  the obvious follow-ups.
