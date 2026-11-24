# URL Shortener

## Overview

A Spring Boot URL shortener service. Callers submit a long URL and receive a short code, and visiting the short code redirects to the original URL.

Features:

- Optional custom alias used as the short code.
- Optional time-to-live (`ttlSeconds`). After expiry the link returns 410 Gone.
- Per-client-IP rate limiting on link creation (429 Too Many Requests when exceeded).
- Existing behaviour is unchanged: requests without `customAlias` or `ttlSeconds` get a generated code, and existing links never expire.

Design notes:

- Aliases and generated codes share one unique namespace. Uniqueness is enforced by a database unique constraint on the code.
- Expiry is stored as an absolute UTC timestamp and checked against the current time on each request. Links without expiry have a null value.
- Rate limiting is an in-memory, thread-safe, fixed-window limiter per instance. State resets on restart. The client IP is the servlet remote address, and `X-Forwarded-For` is not trusted.
- Every creation request counts toward the limit regardless of outcome. The check runs before body validation, so 429 takes precedence over 400 and 409.
- All errors use one JSON body: `{"status": <int>, "error": "<text>", "message": "<text>"}`. Stack traces are never returned.

## API

### `POST /shorten`

Create a short link.

Request body (JSON):

| Field | Type | Required | Notes |
|---|---|---|---|
| `url` | string | yes | Must be a valid http or https URL. |
| `customAlias` | string | no | Must match `^[A-Za-z0-9_-]+$`, 3 to 32 characters, case-sensitive. Reserved route names (e.g. `shorten`) are rejected. |
| `ttlSeconds` | integer | no | Strictly a positive integer. If omitted, the link never expires. Zero, negative, fractional (`1.5`) and non-numeric (`"abc"`) values are rejected. |

Example:

```json
{ "url": "https://example.com/some/long/path", "customAlias": "my-link", "ttlSeconds": 3600 }
```

Success response: `201 Created`

```json
{ "code": "my-link" }
```

The `code` equals `customAlias` when one is given, otherwise it is generated.

Status codes:

| Status | Meaning |
|---|---|
| 201 | Link created. |
| 400 | Invalid or missing `url`, malformed JSON body, invalid alias (bad characters, wrong length, reserved name) or invalid `ttlSeconds`. No link is created. |
| 409 | The alias is already in use. The existing link is not modified. |
| 429 | The client IP exceeded the creation rate limit. |

### `GET /{code}`

Resolve a short code and redirect to the original URL.

Path parameter: `code`, the generated code or custom alias.

Response: a redirect to the target URL, with the target in the `Location` header.

Status codes:

| Status | Meaning |
|---|---|
| 3xx | Redirect to the original URL. |
| 404 | The code never existed. |
| 410 | The link existed but has expired. |

### Rate limit configuration

Defaults are 10 creation requests per IP per 60-second window. Override them in `application.yml` or application properties:

```yaml
ratelimit:
  max-requests: 10
  window-seconds: 60
```

## Running

```
mvnw spring-boot:run
```

On Windows PowerShell use `.\mvnw spring-boot:run`.

## Testing

```
mvnw test
```

On Windows PowerShell use `.\mvnw test`.
