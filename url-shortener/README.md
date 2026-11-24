# URL Shortener Service

## Overview

A Spring Boot service that shortens URLs and redirects short codes to the original URL. It is hardened against abuse and external-check failures:

- **Rate limiting**: each client IP (taken from the connection's remote address) may send 60 requests per 60 seconds. Further requests in the window get HTTP 429 with a `Retry-After` header. The limits are configurable via `ratelimit.max-requests` and `ratelimit.window-seconds`. State is held in memory (single instance).
- **SSRF protection**: submitted URLs must use `http` or `https`. URLs whose host is `localhost`, or a literal or DNS-resolved loopback, private (RFC 1918), link-local (including 169.254.169.254), IPv6 unique-local or unspecified address, are rejected with HTTP 400 and no outbound call is made.
- **External check resilience**: each external check call times out after 3 seconds and is retried up to 2 more times with a 200 ms fixed backoff. Redirects are not followed. If the check still fails, the API returns HTTP 503 with a JSON error body and nothing is persisted.
- By default the external check uses a local stub that makes no network calls. Set `external-check.mode=http` to use the real HTTP check.

## API

Error responses share one JSON shape: `status`, `error`, `message` and, where applicable, `code`. They never contain stack traces.

### `POST /shorten`

Creates a short code for a URL. The URL is validated (syntax, scheme, SSRF) and externally checked before it is persisted.

Request body (JSON):

```json
{ "url": "https://example.com/page", "customAlias": "my-alias", "ttlSeconds": 3600 }
```

- `url` (required): an http or https URL.
- `customAlias` (optional): custom short code.
- `ttlSeconds` (optional): lifetime of the link in seconds. If omitted, the link does not expire.

Success response: `201 Created`

```json
{ "code": "abc123" }
```

Status codes:

- `201` created.
- `400` invalid or missing URL, malformed JSON, invalid alias, invalid TTL, non-http(s) scheme, or a private/internal URL (the error body names the reason).
- `409` custom alias already exists.
- `429` rate limit exceeded (includes `Retry-After` header).
- `503` external check failed or timed out after retries (JSON error body with an error code and message).

### `GET /{code}`

Redirects to the original URL for the given short code. There is no request body.

Status codes:

- Redirect response with the original URL in the `Location` header for a known, unexpired code.
- `404` unknown code.
- `410` link has expired.
- `429` rate limit exceeded (includes `Retry-After` header).

## Running

```
mvnw spring-boot:run
```

On Windows PowerShell use `.\mvnw spring-boot:run`.

Relevant configuration in `application.yml`:

- `ratelimit.max-requests` (default 60) and `ratelimit.window-seconds` (default 60)
- `external-check.mode` (`stub` by default, or `http`)
- `external-check.timeout-ms` (3000), `external-check.max-retries` (2), `external-check.backoff-ms` (200)

## Testing

```
mvnw test
```

The tests cover the acceptance criteria, including rate limiting, rejection of unsafe URLs and the 503 behaviour when the external check fails.
