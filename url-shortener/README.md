# Minimal URL Shortener Service

## Overview

A small Spring Boot 3 (Java 21) service that turns a long URL into a short code and redirects visitors from the short code to the original URL.

- Only absolute `http` and `https` URLs with a non-empty host are accepted.
- Each request to shorten generates a new unique 7-character base62 code (no deduplication of identical URLs).
- Mappings are stored in a file-based H2 database via Spring Data JPA, so they survive restarts. The schema is created automatically at startup. Code uniqueness is enforced by a unique constraint on the `code` column, and a collision results in regeneration rather than overwriting.
- No authentication, no analytics (no visit counts are stored or exposed), and no calls to external services.
- Errors are returned as a consistent JSON body and never expose stack traces.

## API

### `POST /shorten`

Creates a short code for a URL.

**Request body** (`application/json`):

```json
{ "url": "https://example.com/some/long/path" }
```

**Responses**

| Status | Meaning | Body |
|--------|---------|------|
| `201 Created` | URL stored | `{ "code": "aB3dE9x" }` |
| `400 Bad Request` | `url` missing, blank, malformed, or scheme is not `http`/`https`; nothing is persisted | Error JSON |

### `GET /{code}`

Redirects to the original URL for a known code.

**Responses**

| Status | Meaning | Body |
|--------|---------|------|
| `302 Found` | Code is known | Empty body; `Location` header set to the original URL |
| `404 Not Found` | Code is unknown | Error JSON |

### Error body

All error responses use the same JSON shape:

```json
{ "status": 400, "error": "Bad Request", "message": "..." }
```

Unexpected server errors return a generic message with status `500`; no stack traces are exposed.

## Running

```
mvnw spring-boot:run
```

The service uses a file-based H2 database as its only datastore, so stored mappings persist across restarts.

## Testing

```
mvnw test
```
