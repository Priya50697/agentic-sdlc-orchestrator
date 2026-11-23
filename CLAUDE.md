# Agentic SDLC Orchestrator + URL Shortener

Interview project: an orchestrator (Java 21, Spring Boot 3.5, Maven) that drives a requirement through the SDLC
with a dependency graph, gates, parallel+join, human approvals, bounded retry/rollback/safe-stop, policy
guardrails, hash-chained audit and reliability metrics. Target app: `url-shortener/` (generated).

**Read first:** `docs/plan/progress.md` (what is done, deviations, open issues), then `docs/plan/00-foundational-plan.md` only for the phase you are on.

## Phases (update checkbox + progress.md at the END of every phase, before /clear)
- [x] P1 llm/, observability/, persistence/, pom+mvnw, CLAUDE.md
- [x] P2 engine/ + governance/ + engine tests with FakeLlmClient
- [x] P3 workspace/ (scaffold, git, maven, sandbox), context/ (CodeIndexer), REST + console
- [x] P4 7 agents + prompts + schemas; dry-run greenfield
- [x] P5 live Greenfield, export to docs/runs/
- [ ] P6 Brownfield + Ambiguous
- [ ] P7 docs 01-04, README, final replay run

## Commands (Windows; no system Maven)
- Build+test: `cd orchestrator; .\mvnw.cmd test` (PowerShell) — exit code 0 = green
- Live CLI smoke test (costs ~$0.0002): set `CLAUDE_CLI_LIVE=1`, run `.\mvnw.cmd test`
- Scaffold build check (needs Maven repo access): set `SCAFFOLD_LIVE=1`, run `.\mvnw.cmd test -Dtest=ScaffoldBuildLiveTest`
- Fixture build check (real Maven on the dry-run fixtures): `SCAFFOLD_LIVE=1`, `.\mvnw.cmd test -Dtest=FixtureBuildLiveTest`
- Console run (greenfield needs the scenario even with a file): `.\mvnw.cmd spring-boot:run -Dspring-boot.run.arguments="--orchestrator.cli.scenario=greenfield [--orchestrator.cli.requirement-file=../docs/runs/minimal-requirement.md] --spring.main.web-application-type=none"`
- Anthropic API profile: `-Dspring.profiles.active=anthropic` + env `ANTHROPIC_API_KEY`
- Regenerate wrapper (only if lost): IntelliJ's `plugins/maven-plugin/lib/maven3/bin/mvn.cmd wrapper:wrapper -Dmaven=3.9.9`

## Code map (`orchestrator/src/main/java/com/sdlcagent/orchestrator/`)
- `llm/` LlmClient + ClaudeCliAdapter (default), SpringAiAnthropicClient (profile `anthropic`), CachingLlmClient (record|replay|off), FakeLlmClient (tests), JsonResponseParser, LlmConfig/LlmProperties
- `observability/` AuditLogger (hash-chained JSONL + `verify`), AuditEventType, MetricsService (derives all metrics from audit)
- `persistence/` RunRepository (atomic `state.json` per run), RunPaths (all `.orchestrator/` paths)
- `engine/` WorkflowEngine (coordinator; `start/run/resume/abort/updateArtifact`), Node, RunSnapshot, StageGraph, TaskGraph, Scheduler, Invalidator, FailureRouter, EngineConfig (pluggable executors/gates/expanders/policies/checkpoints)
- `governance/` Gate, Policy/PolicyEngine/Policies, RiskClassifier, HumanApprovalManager, FileChange
- `workspace/` Scaffolder, WorkspaceGit (CheckpointService), SandboxedFileWriter (ChangeApplier), MavenRunner, SurefireParser (+ `classify` -> FailureInfo), WorkspaceConfig/Properties
- `context/` CodeIndexer -> CodeIndex, ContextAssembler (char budget)
- `service/` RunService (facade for REST+console), EngineConfigCustomizer (P4 registers agents/gates/expanders as beans of this type)
- `api/` RunController, ApprovalController; `cli/` ConsoleRunner/ConsoleSession/Scenarios
- `agents/livecheck/` advisory live-check stage (LiveAppRunner seam, ProcessAppRunner, LiveProbes) between implementation and release
- `agents/` base/ (SdlcAgent, AbstractLlmAgent, AgentRegistrar, PromptLoader) + one package per agent with its gate(s); prompts in `resources/prompts/`; per-agent write scope in `governance/AgentPermissions`

## Core contracts
- `LlmResponse complete(LlmRequest)`; `LlmRequest(system, user, model?, timeout?)`; `LlmResponse(text, model, inputTokens, outputTokens, costUsd, durationMs, cacheHit)`; failures throw `LlmException(Kind)`
- `AuditLogger.log(AuditEventType, nodeId, Map data)`; MetricsService relies on: NODE_PASSED/NODE_FAILED (success rate, MTTR per nodeId), RETRY, ROUTE_BACK, ROLLBACK, INVALIDATED, POLICY_VIOLATION, HUMAN_APPROVAL, AGENT_END data keys `inputTokens, outputTokens, costUsd, cacheHit`, RUN_STARTED / RUN_COMPLETED|RUN_SAFE_STOPPED|RUN_ABORTED (E2E latency)
- Node states: PENDING, READY, RUNNING, VALIDATING, PASSED, FAILED, RETRYING, ROLLED_BACK, WAITING_APPROVAL, SKIPPED, INVALIDATED
- Run states: CREATED, RUNNING, WAITING_HUMAN, COMPLETED, SAFE_STOPPED, ABORTED
- Bean `LlmClient` (primary) = CachingLlmClient wrapping the `@Qualifier("raw")` backend

## Conventions
- Single-threaded coordinator owns all state changes; workers on virtual threads; agents are thin (context in, prompt, LlmClient, JsonResponseParser, typed record). Decisions live in gates/policies, never in prompts.
- FailureRouter and policies are rules only (no LLM). Engine tests use FakeLlmClient / replay fixtures, never the real CLI.
- Workspace git uses `--git-dir=.orchestrator/workspace.git --work-tree=url-shortener/`; never run git reset near orchestrator code.
- `claude -p` runs from a neutral temp cwd with tools, settings, MCP and skills disabled; prompt via stdin.

## Don'ts
- Don't commit `.orchestrator/`, `target/`, or keys. Don't add Ollama. Don't call the real LLM in unit tests.
- Don't put implementation detail or file listings in this file; put handoff notes in progress.md.
- PowerShell 5.1 drops empty-string args (`--tools ""`); use the Bash tool or Java ProcessBuilder for those.
