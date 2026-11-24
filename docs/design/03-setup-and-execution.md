# 03 - Setup and Execution

## Prerequisites

- JDK 21 on `PATH` (or `JAVA_HOME`).
- Git. The orchestrator drives its own workspace git via `--git-dir`; your global git identity is not touched.
- **No system Maven needed.** Both `orchestrator/` and the generated `url-shortener/` ship a Maven wrapper
  (`mvnw` / `mvnw.cmd`). First build downloads dependencies, so network access to Maven Central is required once.
- For live runs: the Claude Code CLI (`claude`) installed and logged in. The default backend calls `claude -p`
  (subscription); no API key is needed. The optional `anthropic` profile uses the Anthropic API instead.

Commands below are for Windows (the project was built there). On Linux/macOS use `./mvnw` instead of
`.\mvnw.cmd`.

## Build and test

```powershell
cd orchestrator
.\mvnw.cmd test        # exit code 0 = green; 124 @Test methods, live tests are opt-in and skipped
```

Opt-in live tests (off by default, they need a real CLI or Maven):

| Check | How |
|---|---|
| `claude -p` smoke test (~$0.0002) | `CLAUDE_CLI_LIVE=1`, then `.\mvnw.cmd test` |
| Scaffold compiles with its own wrapper | `SCAFFOLD_LIVE=1`, `.\mvnw.cmd test -Dtest=ScaffoldBuildLiveTest` |
| Dry-run fixtures build under real Maven | `SCAFFOLD_LIVE=1`, `.\mvnw.cmd test -Dtest=FixtureBuildLiveTest` |
| Test-engineer probe against the real LLM (~$0.2-0.3) | `TESTENG_PROBE=1` (needs `.orchestrator/` from a prior run) |

## Running a scenario (console)

Three scenario requirements ship in `orchestrator/src/main/resources/scenarios/`: `greenfield`, `brownfield`,
`ambiguous`. From `orchestrator/`:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--orchestrator.cli.scenario=greenfield --spring.main.web-application-type=none"
```

- `--orchestrator.cli.scenario=` greenfield | brownfield | ambiguous. **Greenfield must be named explicitly**, even
  with `--orchestrator.cli.requirement-file=<path>`; a file alone becomes scenario `custom`, which is not scaffolded.
- The bundled `greenfield` scenario text (analytics + URL-safety provider) is richer than what the exported greenfield
  run used. That run was started with `--orchestrator.cli.requirement-file=../docs/runs/greenfield/requirement-input.md`
  (the minimal requirement: shorten, redirect, 404, 400, H2, no analytics, no providers) **together with**
  `scenario=greenfield`.
- `--orchestrator.cli.resume=<runId>` continues a persisted run.
- The console prints each approval request. Answer `a` to approve or `r <feedback>` to reject with feedback. End of
  input is treated as a safe-stop.

Approvals you should expect per scenario:

| Scenario | Prompts |
|---|---|
| Greenfield | Gate 1 (architecture), Gate 2 (release) |
| Brownfield | Gate 1, `RISK_ACTION` when the test engineer changes an existing test, Gate 2 |
| Ambiguous | Gate 0 (blocking ambiguities with proposed defaults), Gate 1, Gate 2. Rejecting Gate 0 once with feedback (we used `r also block private and internal URLs`) shows the replan |

**Brownfield prerequisite:** the workspace `url-shortener/` is the baseline and must already contain the app. Do
not delete it. At run start the engine commits a `run <id> start` checkpoint and snapshots `src/test`. Ambiguous
was run on top of the committed brownfield result.

**Greenfield prerequisite:** delete `url-shortener/` and `.orchestrator/` first (stale workspace and run state).

## REST mode

Start the orchestrator with the web server on (omit `--spring.main.web-application-type=none`) and use
`POST /api/runs`, `GET /api/runs/{id}`, `GET /api/runs/{id}/approvals`, `POST /api/runs/{id}/approvals/{aid}`,
`GET /api/runs/{id}/metrics`, `GET /api/runs/{id}/audit/verify`. There is no authentication (local demo tool).
Status reads the persisted snapshot, so right after an approval it can still say WAITING_HUMAN; look at
`pendingApprovals`.

## LLM backends and cache

| Setting | Values |
|---|---|
| `orchestrator.llm.model` | `sonnet` (default, `claude -p` alias) |
| `orchestrator.llm.cache-mode` | `record` (default), `replay`, `off` |
| Profile `anthropic` | `-Dspring.profiles.active=anthropic` plus env `ANTHROPIC_API_KEY` |

`record` stores each reply in `.orchestrator/llm-cache` keyed by hash(model + system + user). `replay` serves only
from the cache, so a run costs nothing and is reproducible; a cache miss fails the call. Practice runs fill the
cache, so before an export-quality live run, move the cache aside and confirm `cacheHits` is 0 in
`metrics-summary.json`.

## Where output goes

```
.orchestrator/runs/<runId>/
  state.json            persisted snapshot (resume point)
  audit.jsonl           hash-chained events
  metrics-summary.json  written when the run ends
  artifacts/            spec, impact, architecture-plan, task-graph, test-report, doc-report, live-report, release-report
  baseline-tests/       brownfield only: src/test snapshot at run start
```

There is no export command. Finished runs were copied by hand to `docs/runs/<scenario>/` together with the
console log (`run.log`) and `requirement-input.md`.

## Verifying a run

1. `metrics-summary.json`: `outcome` is COMPLETED, `cacheHits` is 0 for a live run.
2. Audit chain: `GET /api/runs/{id}/audit/verify` (REST), or `AuditLogger.verify(path)` from a test or jshell.
3. Generated app tests: `cd url-shortener; .\mvnw.cmd test`.
4. Smoke test: `.\mvnw.cmd spring-boot:run`, then
   `curl -X POST localhost:8080/shorten -H "Content-Type: application/json" -d '{"url":"https://example.com"}'`
   and follow the returned code with `curl -i localhost:8080/<code>` (expect 302). The exact request and response
   shapes are in `url-shortener/README.md`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Greenfield fails at `wave:0`, no `mvnw.cmd` in workspace | Run started without `scenario=greenfield`; it was not scaffolded |
| Brownfield run starts from a dirty tree | Reset `url-shortener/` to the intended baseline commit first; the dirty tree becomes the baseline otherwise |
| Safe-stop "back-edge budget exhausted" or "not converging" | Same compile or exception defect repeated; read the `ROUTE_BACK` / `NODE_FAILED` events in `audit.jsonl` |
| PowerShell drops an empty `--tools ""` argument | Use Git Bash or Java `ProcessBuilder` (the adapter already does) |
| Test output shows Mockito self-attach warnings | Harmless on JDK 21 |
