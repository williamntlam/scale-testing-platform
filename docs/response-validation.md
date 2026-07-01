# Response Validation

When your workers receive HTTP responses from the target system, **do not trust them blindly**. A misconfigured endpoint, error page, or hostile target can return huge or unexpected bodies. Under high concurrency, that can cause **out-of-memory (OOM) errors**, **GC pauses**, or **misleading success metrics**.

Validate and sanitize responses in the **`service/`** layer **after** the HTTP call and **before** you build a `TestResponse` or `LoadTestResponse`.

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
| Redirect misconfiguration | Login page HTML | Huge body, sensitive content in logs |
| Debug endpoint left on | Stack traces | Large + sensitive |

---

## What to check

### 1. HTTP status code

Treat non-2xx as failure even if the body parses:

```text
2xx → candidate for SUCCESS (after other checks)
4xx / 5xx → FAILED
```

Do not rely on body content alone.

### 2. Response body size

Set a **hard max byte limit** per response (start with **64 KB–256 KB** for JSON APIs; tune per workload).

| Over limit | Action |
|------------|--------|
| Body too large | Mark `TestStatus.FAILED` (or `SUSPICIOUS`) |
| Store in `TestResponse` | Truncated summary only, e.g. `"[truncated: 2.4 MB]"` |
| Never | Store the full body in memory or return it in `LoadTestResponse` |

Use `HttpResponse.BodyHandlers.ofByteArray()` or a **limited InputStream** so you never read unbounded data into a `String`.

### 3. Content-Type

If you expect JSON from the target:

```text
Expected: application/json
Received: text/html  →  flag as suspicious / failed
```

Allow a small allowlist (e.g. `application/json`, `application/problem+json`).

### 4. Suspicious content patterns

Flag (do not necessarily fail the whole run) when the body:

- Starts with `<!DOCTYPE html>` or `<html` (HTML error page)
- Contains obvious stack-trace markers (`Exception`, `at com.`, `Traceback`)
- Is empty when a non-empty JSON object was expected
- Has unexpected binary / non-UTF-8 content
- Exceeds max nesting depth or field count (if parsing JSON)

Keep checks **cheap** (prefix scan, length, headers) — avoid full JSON parse on every response in the hot path unless you need it.

### 5. Sensitive data

Do not echo back into `LoadTestResponse`:

- API keys, tokens, cookies
- Full credit-card or PII patterns (basic regex redaction if logging)

Prefer storing **length + hash prefix** or **first N characters** for debugging.

---

## Where it fits in MVC

```text
Worker receives HttpResponse
        ↓
service/ResponseValidator.validate(response)   ← you implement this
        ↓
TestResponse(taskId, status, safeSummary)      ← small, bounded
        ↓
LoadTestResponse (aggregated, still bounded)
```

| Layer | Responsibility |
|-------|----------------|
| **`service/`** | Size limits, status checks, truncation, suspicious detection |
| **`model/`** | Hold **validated** `TestResponse` — optional `TestStatus.SUSPICIOUS` enum value later |
| **`controller/`** | Return `LoadTestResponse` — already sanitized by service |
| **`config/`** | `maxResponseBytes`, allowed content types, timeouts |

Models should not perform validation logic — only hold the result.

---

## Suggested `TestResponse` shape (after validation)

Keep what you have; put **safe, bounded** text in `responseBody`:

```java
// Examples of what responseBody might contain after validation:
"{"id":"abc123"}"                    // normal small JSON
"[truncated: 1.2 MB, sha256=ab12…]"  // over size limit
"unexpected content-type: text/html" // suspicious
"HTTP 502 Bad Gateway"               // non-2xx summary
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

## Implementation approach (Strategy pattern)

Define a small interface in `service/`:

```java
public interface ResponseValidator {
    ValidatedResponse validate(int httpStatus, HttpHeaders headers, byte[] body);
}
```

```java
public record ValidatedResponse(
    TestStatus status,
    String safeBody,
    int originalByteCount
) {}
```

Default implementation: `DefaultResponseValidator` in `service/` with limits from `config/`.

Workers call the validator once per task:

```text
byte[] body = readWithLimit(inputStream, maxBytes)
ValidatedResponse v = responseValidator.validate(statusCode, headers, body)
results.set(taskId, new TestResponse(taskId, v.status(), v.safeBody()))
```

---

## Configuration sketch

In `application.yaml` (when you add config):

```yaml
scale-testing:
  response:
    max-body-bytes: 65536          # 64 KB
    allowed-content-types:
      - application/json
      - application/problem+json
    truncate-preview-chars: 200    # max chars stored in responseBody
```

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

Write unit tests for `ResponseValidator` (no Spring required):

- [ ] Body under limit → `SUCCESS`, body preserved (or preview)
- [ ] Body over limit → `FAILED`, truncated message, no full body stored
- [ ] HTTP 500 → `FAILED`
- [ ] HTTP 200 + `text/html` → `FAILED` or `SUSPICIOUS`
- [ ] HTTP 200 + valid small JSON → `SUCCESS`
- [ ] Empty body when JSON expected → `FAILED` or `SUSPICIOUS`

---

## When to add this

| Phase | Action |
|-------|--------|
| **Step 2** (service / engine) | Read response with a **byte limit** from day one |
| **Step 2–3** | Add `ResponseValidator` before writing `TestResponse` |
| **Step 4** (controller) | Ensure API never returns unbounded `responseBody` arrays |

**Minimum viable guard:** even without a full validator class, cap bytes read and truncate before `new TestResponse(...)`. Expand into `ResponseValidator` when checks grow.

---

## Related docs

- [MVC Structure](./mvc-structure.md) — where validation runs in the service layer
- [Design Patterns](./design-patterns.md) — Strategy pattern for pluggable validators
