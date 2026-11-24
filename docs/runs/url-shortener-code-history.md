# url-shortener code history: what each run changed

The three scenarios build on each other: brownfield extends the greenfield app, and ambiguous extends the brownfield
result. Each state is a git tag, so any step can be inspected or diffed.

| Tag | Commit | State of `url-shortener/` | Run | Tests passing |
|---|---|---|---|---|
| `run-greenfield` | `314ce0f` | the app as generated from nothing | `run-20261123-063431-d43d` | 25 |
| `run-brownfield` | `6c50e79` | greenfield + aliases, expiry, rate limit | `run-20261124-142820-c6a0` | 40 |
| `run-ambiguous` | `ca8cd1b` | brownfield + SSRF blocking, external check with fail-closed handling, rate-limit hardening | `run-20261124-143909-7885` | 70 |

Note: the greenfield export itself (audit, metrics, artifacts) was committed earlier as `203b0bf`; the generated code
was first committed in `314ce0f`. Test counts come from each run's `test-report`.

## See the changes yourself

```bash
git diff --stat run-greenfield run-brownfield -- url-shortener     # brownfield: what changed
git diff run-greenfield run-brownfield -- url-shortener/src/main   # full main-code diff
git diff --stat run-brownfield run-ambiguous -- url-shortener      # ambiguous: what changed
git show run-greenfield --stat -- url-shortener                    # greenfield: the whole app
```

## Greenfield (`run-greenfield`): 21 files, +1,184 lines

Requirement: shorten a URL, 302 redirect, 404 for unknown codes, 400 for invalid URLs, H2 storage, no analytics.

| Layer | Files |
|---|---|
| controller | `UrlShortenerController` (`POST /shorten`, `GET /{code}`), `GlobalExceptionHandler` |
| service | `UrlShortenerService`, `CodeNotFoundException` |
| dao | `UrlMapping` (entity), `UrlMappingRepository` |
| dto | `ShortenRequest`, `ShortenResponse`, `ErrorResponse` |
| validator | `ValidUrl`, `HttpUrlValidator` (absolute http/https only) |
| util | `CodeGenerator` |
| tests | `UrlShortenerAcceptanceTest` (229 lines, one test per acceptance criterion) |
| other | `Application`, `application.yml`, `pom.xml`, `README.md` (scaffold + docs) |

## Brownfield (`run-greenfield` -> `run-brownfield`): 12 files added, 9 modified

Requirement: custom aliases, expiry with 410, per-IP rate limit with 429; existing behaviour must keep working.

**Added (main):**
- Custom alias: `AliasValidator`, `InvalidAliasException`, `AliasAlreadyExistsException`
- Expiry: `TtlValidator` (strict integer `ttlSeconds`), `InvalidTtlException`, `LinkExpiredException` (410), `ClockConfig`
- Rate limit: `RateLimitService`, `RateLimitInterceptor`, `WebMvcConfig`, `RateLimitExceededException` (429)

**Added (tests):** `UrlShortenerFeaturesTest` (182 lines).

**Modified:** `UrlMapping` (nullable `expiresAt` + constructor), `ShortenRequest` (alias, ttlSeconds),
`UrlShortenerService` (+66/-4: alias, TTL and expiry logic), `UrlShortenerController`, `GlobalExceptionHandler`
(new error mappings), `CodeGenerator`, `application.yml` (rate-limit settings), `README.md`.

**Baseline test changed on purpose:** `UrlShortenerAcceptanceTest#ac6_noAnalyticsStored` asserted the entity's exact
field list `[id, code, originalUrl]`, which the spec's new `expiresAt` field legitimately breaks. The test engineer
changed it under the baseline-test contract: declared in `supersededTests` with a reason, approved by a human as a
`RISK_ACTION`, and audited as `BASELINE_TEST_SUPERSEDED`. It still forbids analytics fields. No other baseline test
was removed or renamed.

## Ambiguous (`run-brownfield` -> `run-ambiguous`): 10 files added, 10 modified

Requirement: "Make it safer against abuse and more reliable when external checks fail." Gate 0 was rejected once with
"also block private and internal URLs", so SSRF blocking became part of the spec.

**Added (main):**
- SSRF / private-URL blocking: `SsrfUrlValidator` (145 lines), `UnsafeUrlException`
- External check with resilience: `ExternalCheckProvider` (interface), `HttpExternalCheckProvider` (166 lines,
  no redirects, per-attempt timeout, bounded fixed-backoff retries), `StubExternalCheckProvider`, `ExternalCheckProperties`,
  `ExternalCheckUnavailableException` (503, fail closed: no check result means the request is rejected)

**Added (tests):** `SsrfAndFailureHandlingTest`, `ExternalCheckProviderTest`, `RateLimitHardeningTest`.

**Modified:** `UrlShortenerService` (calls the validator and provider), `RateLimitService` /
`RateLimitInterceptor` / `RateLimitExceededException` / `WebMvcConfig` (the limit now covers all requests, not only
`POST`; keyed on the socket address, `X-Forwarded-For` ignored; 429 carries a `Retry-After` header),
`GlobalExceptionHandler` and `ErrorResponse` (new error shapes), `HttpUrlValidator`, `application.yml`, `README.md`.

**Effect:** the live-check hardening probe (`http://169.254.169.254/...` must not be accepted) failed on the
greenfield and brownfield runs and passed here.

## How these were produced

Every file above was written by an agent and applied by the orchestrator through the sandboxed writer, after the
policy scan and exit gates. Per-run evidence (audit events for each file change, approvals, retries) is in
`docs/runs/<scenario>/audit.jsonl`; the architect's task list for each run is `docs/runs/<scenario>/artifacts/task-graph`.
