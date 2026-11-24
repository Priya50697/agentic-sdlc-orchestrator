# Agentic SDLC Orchestrator + URL Shortener

An orchestrator (Java 21, Spring Boot 3.5, Maven) that drives a requirement through the software lifecycle:
requirements -> design -> implementation -> test -> docs -> release readiness. Seven LLM-backed agents do the
drafting; **deterministic code** (a dependency graph, gates, policies, failure routing, budgets) decides what is
accepted, retried, rolled back or stopped. It is proven on a generated URL shortener (`url-shortener/`) in three
scenarios: greenfield, brownfield and ambiguous.

## What it demonstrates

| Capability | Where |
|---|---|
| Explicit dependency graph: fixed stage graph + runtime task graph in parallel waves | `engine/StageGraph`, `TaskGraph`, `Scheduler` |
| Entry/exit gates on every stage | `agents/*/…Gate`, `governance/Gate` |
| Parallel paths and a join (testing, docs and live-check, then release) | `StageGraph.standard` |
| Human approvals (Gate 0 ambiguities, Gate 1 design, Gate 2 release, risky actions) via console or REST | `governance/HumanApprovalManager` |
| Bounded retry, route-back to the owning task, rollback, safe-stop | `engine/FailureRouter`, `WorkflowEngine` |
| Dynamic re-planning from artifact hashes | `engine/Invalidator` |
| Policy guardrails and per-agent write permissions | `governance/Policies`, `AgentPermissions` |
| Tamper-evident audit trail (hash-chained JSONL) | `observability/AuditLogger` |
| Reliability metrics: success rate, retries, rollbacks, MTTR, end-to-end latency, calls, tokens, cost | `observability/MetricsService` |

## Results of the three live runs

The scenario requirements live in `orchestrator/src/main/resources/scenarios/`; running one writes its state to
`.orchestrator/runs/<id>/` (gitignored), and the finished runs were copied to `docs/runs/<scenario>/` (artifacts, audit
logs, metrics, console logs, and the requirement that was run). All used `claude -p` with `sonnet`.

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Outcome | COMPLETED | COMPLETED | COMPLETED |
| LLM calls / cost | 14 / $0.56 | 17 / $1.07 | 14 / $0.89 |
| End-to-end | 203 s | 311 s | 241 s |
| Shows | scaffold, task waves, parallel join, Gates 1-2 | impact analysis, a failure that was recovered (MTTR ~94.5 s), a baseline test changed under a risk approval | Gate 0 rejected once with feedback, which invalidated and re-planned the requirements |

Caveats (details in [docs/design/04-testing-and-tradeoffs.md](docs/design/04-testing-and-tradeoffs.md)): the
greenfield run used a minimal requirement and predates the system-prompt fix, so brownfield and ambiguous are the
runs with full prompts; brownfield did not exercise a Gate 1 rejection.

## Quick start (Windows; no system Maven needed)

Prerequisites: JDK 21, git, and for live runs the Claude Code CLI (`claude`) logged in.

```powershell
cd orchestrator
.\mvnw.cmd test        # unit tests, no real LLM or Maven process; exit code 0 = green
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--orchestrator.cli.scenario=ambiguous --spring.main.web-application-type=none"
```

The `spring-boot:run` line is a live run (about $1 of `claude -p` usage). The console asks for each approval: `a` to approve, `r <feedback>` to reject with feedback. Brownfield and ambiguous
build on the existing `url-shortener/`, so do not delete it. Greenfield needs `url-shortener/` and `.orchestrator/`
removed first. `orchestrator.llm.cache-mode=replay` re-runs a recorded scenario from `.orchestrator/llm-cache` at no
cost. Full instructions, REST mode and troubleshooting: [docs/design/03-setup-and-execution.md](docs/design/03-setup-and-execution.md).

## Repository layout

```
orchestrator/      the orchestrator (engine, governance, agents, llm, workspace, context, observability, api, cli)
  src/main/resources/scenarios/   the three scenario requirements (greenfield.md, brownfield.md, ambiguous.md)
  src/main/resources/prompts/     one prompt per agent
url-shortener/     generated target app (tracked as a deliverable); has its own README
docs/design/       01 architecture, 02 agent specifications, 03 setup and execution, 04 testing and trade-offs
docs/runs/         one folder per scenario run: requirement-input.md (the requirement actually run), audit.jsonl,
                   metrics-summary.json, state.json, artifacts/, run.log
                   url-shortener-code-history.md: what each run added and changed in url-shortener/
docs/plan/         progress-tracker.md: phase-by-phase handoff log (deviations, open issues)
.orchestrator/     gitignored run state, workspace git and LLM cache
```

## Documentation

1. [Architecture overview](docs/design/01-architecture-overview.md): graphs, engine, governance, observability
2. [Agent specifications](docs/design/02-agent-specifications.md): each agent's inputs, outputs, gates and permissions
3. [Setup and execution](docs/design/03-setup-and-execution.md): build, run, approve, verify
4. [Testing and trade-offs](docs/design/04-testing-and-tradeoffs.md): test strategy, run comparison, deviations, limits
5. [url-shortener code history](docs/runs/url-shortener-code-history.md): files added and changed in the app by each run

## Known limitations

No authentication on the REST API (local demo tool); no per-node wall-clock timeout; the live-check stage is advisory,
not blocking; exports to `docs/runs/` are manual copies. See doc 04, section 6.
