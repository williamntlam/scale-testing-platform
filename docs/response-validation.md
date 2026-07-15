# Response Validation

When your workers receive HTTP responses from the target system, **do not trust them blindly**. A misconfigured endpoint, error page, or hostile target can return huge or unexpected bodies. Under high concurrency, that can cause **out-of-memory (OOM) errors**, **GC pauses**, or **misleading success metrics**.

Validate and sanitize responses in the **`services/`** layer **after** the HTTP call and **before** you build a `TestResponse` or `LoadTestResponse`.

---

## Current status

| Check | Status | Where |
|-------|--------|--------|
| Non-2xx → `FAILED` | **Done** | `LoadTestService.executeTask` |
| Body length > 65,536 → `FAILED` with `[truncated: N bytes]` | **Done** | `LoadTestService` (`MAX_RESPONSE_BYTES`) |
| Read as `byte[]` (not unbounded stream into String first) | **Done** | `HttpRequestExecutor` uses `BodyHandlers.ofByteArray()` |
| Content-Type allowlist | Planned | — |
| Suspicious content patterns (HTML, stack traces) | Planned | — |
| Sensitive-data redaction | Planned | — |
| Pluggable `ResponseValidator` Strategy | Planned | `ValidatedResponse` record exists in `services/port/` |

**Minimum viable guard is already in place.** Expand into a dedicated validator when checks grow.

---

## Why this matters in a load tester

You may fire **thousands of concurrent requests**. If each worker holds a 10 MB error page in memory:

```text
1,000 workers × 10 MB = ~10 GB in response bodies alone
```

That defeats the purpose of your lightweight list-driven pipeline and claim-check design.

Common real-world cases:

| Scenario | What comes back | Risk |
|----------|-----------------|------|
| Wrong URL | HTML 404 page from a proxy | Large body, looks like "success" if you only check HTTP 200 |
| API gateway error | JSON or HTML error payload | Misleading `responseBody` in results |
| Compromised / malicious target | Multi-megabyte or deeply nested JSON | OOM, slow parsing |
| Redirect misconfiguration | Login page HTML | Huge body, sensitive content in logs *(client currently never follows redirects)* |
| Debug endpoint left on | Stack traces | Large + sensitive |

---

## What to check

### 1. HTTP status code — done

Treat non-2xx as failure even if the body parses:

```text
2xx → candidate for SUCCESS (after other checks)
4xx / 5xx → FAILED  ("HTTP {statusCode}")
```

Do not rely on body content alone.

### 2. Response body size — done (fixed 64 KB)

Hard max is currently **`MAX_RESPONSE_BYTES = 65_536`** in `LoadTestService`.

| Over limit | Action |
|------------|--------|
| Body too large | Mark `TestStatus.FAILED` |
| Store in `TestResponse` | `"[truncated: N bytes]"` only |
| Never | Store the full oversize body in `LoadTestResponse` |

**Gap to close later:** prefer a **limited InputStream** / capped reader so oversized responses are never fully buffered in `byte[]` first. Today `ofByteArray()` still loads the entire response into memory before the length check.

### 3. Content-Type — planned

If you expect JSON from the target:

```text
Expected: application/json
Received: text/html  →  flag as suspicious / failed
```

Allow a small allowlist (e.g. `application/json`, `application/problem+json`).

### 4. Suspicious content patterns — planned

Flag (do not necessarily fail the whole run) when the body:

- Starts with `<!DOCTYPE html>` or `<html` (HTML error page)
- Contains obvious stack-trace markers (`Exception`, `at com.`, `Traceback`)
- Is empty when a non-empty JSON object was expected
- Has unexpected binary / non-UTF-8 content
- Exceeds max nesting depth or field count (if parsing JSON)

Keep checks **cheap** (prefix scan, length, headers) — avoid full JSON parse on every response in the hot path unless you need it.

### 5. Sensitive data — planned

Do not echo back into `LoadTestResponse`:

- API keys, tokens, cookies
- Full credit-card or PII patterns (basic regex redaction if logging)

Prefer storing **length + hash prefix** or **first N characters** for debugging.

---

## Where it fits in MVC

```text
Worker receives OutboundResponse
        ↓
LoadTestService.executeTask (inline checks today)
  — later: services/ResponseValidator.validate(...)
        ↓
TestResponse(taskId, status, safeSummary)      ← small, bounded
        ↓
LoadTestResponse (aggregated, still bounded)
```

| Layer | Responsibility |
|-------|----------------|
| **`services/`** | Size limits, status checks, truncation, suspicious detection |
| **`model/`** | Hold **validated** `TestResponse` — optional `TestStatus.SUSPICIOUS` enum value later |
| **`controller/`** | Return `LoadTestResponse` — already sanitized by service |
| **`config/`** | `maxResponseBytes`, allowed content types, timeouts *(not wired yet)* |

Models should not perform validation logic — only hold the result.

---

## Current `TestResponse` shape

```java
public record TestResponse(
    int taskId,
    TestStatus status,
    String responseBody      // full UTF-8 body if under limit; summary if failed
) {}
```

Examples of what `responseBody` contains today:

```text
"...json from target..."           // SUCCESS, body under 64 KB
"HTTP 502"                         // non-2xx
"[truncated: 2400000 bytes]"       // over size limit
"connection timed out"             // exception message
```

Optional later: add fields without bloating the object:

```java
public record TestResponse(
    int taskId,
    TestStatus status,
    String responseBody,      // bounded summary only
    int httpStatusCode,       // optional
    int originalBodyBytes     // optional; 0 if unknown
) {}
```

---

## Next step: Strategy pattern

A `ValidatedResponse` record already exists:

```java
public record ValidatedResponse(
    TestStatus status,
    String safeBody,
    int originalByteCount
) {}
```

Extract checks behind:

```java
public interface ResponseValidator {
    ValidatedResponse validate(int httpStatus, /* headers */, byte[] body);
}
```

Default implementation: `DefaultResponseValidator` in `services/` with limits from `config/`.

Workers would then call:

```text
OutboundResponse raw = requestExecutor.send(targetUri, payload)
ValidatedResponse v = responseValidator.validate(raw.statusCode(), headers, raw.body())
results.set(taskId, new TestResponse(taskId, v.status(), v.safeBody()))
```

---

## Configuration sketch (planned)

In `application.yaml` when you externalize limits:

```yaml
scale-testing:
  response:
    max-body-bytes: 65536          # 64 KB (matches current constant)
    allowed-content-types:
      - application/json
      - application/problem+json
    truncate-preview-chars: 200    # max chars stored in responseBody
```

Today the limit is a private static constant, not a Spring property.

---

## `TestStatus` enum options

Today: `SUCCESS`, `FAILED`.

Consider adding later:

| Value | Meaning |
|-------|---------|
| `SUCCESS` | 2xx, within size limits, expected content |
| `FAILED` | Network error, timeout, 4xx/5xx, over size limit |
| `SUSPICIOUS` | 2xx but unexpected type/content (HTML, stack trace) |

Start with `SUCCESS` / `FAILED` only; split `SUSPICIOUS` when you need finer reports.

---

## Testing checklist

Covered by `LoadTestServiceTest` today (against httpbin):

- [x] HTTP 200 + small body → `SUCCESS`
- [x] Multiple payloads → all succeed
- [x] HTTP 500 → `FAILED`

Still worth adding as unit tests when you extract `ResponseValidator`:

- [ ] Body under limit → `SUCCESS`, body preserved (or preview)
- [ ] Body over limit → `FAILED`, truncated message, no full body stored
- [ ] HTTP 200 + `text/html` → `FAILED` or `SUSPICIOUS`
- [ ] Empty body when JSON expected → `FAILED` or `SUSPICIOUS`

---

## Related docs

- [MVC Structure](./mvc-structure.md) — where validation runs in the services layer
- [Design Patterns](./design-patterns.md) — Strategy pattern for pluggable validators
