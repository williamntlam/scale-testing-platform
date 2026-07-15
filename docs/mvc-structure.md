# MVC Project Structure

This project uses **Spring MVC** (REST API) with a simple three-layer layout. Keep it flat until a folder grows uncomfortable — you can split later.

---

## Actual layout

```text
src/main/java/io/github/williamntlam/scale_testing_platform/
├── ScaleTestingPlatformApplication.java
│
├── controller/          # MVC — C (Controller)
│   └── LoadTestController.java
│
├── services/            # MVC — business logic & orchestration
│   ├── LoadTestService.java
│   ├── HttpRequestExecutor.java
│   └── port/
│       ├── RequestExecutor.java
│       ├── OutboundResponse.java
│       └── ValidatedResponse.java   # reserved for pluggable validator
│
├── model/               # MVC — M (Model)
│   ├── LoadTestRequest.java
│   ├── LoadTestResponse.java
│   ├── Task.java                    # unused by engine today; optional concept
│   ├── TestResponse.java
│   └── enums/
│       └── TestStatus.java
│
└── config/              # Spring wiring (not a fourth MVC layer)
    └── HttpClientConfig.java

src/test/java/.../
├── controller/
│   └── LoadTestControllerTest.java
├── services/
│   └── LoadTestServiceTest.java
└── ScaleTestingPlatformApplicationTests.java
```

Package name is **`services/`** (plural), not `service/`.

For a REST API, the **View** is JSON — Spring serializes your model records automatically. You do not need a separate `view/` package unless you add HTML pages later.

---

## Layer responsibilities

### Controller (`controller/`)

**Job:** Accept HTTP requests, validate input shape, call a service, return HTTP responses.

**Should do:**
- Map URLs and HTTP verbs (`@PostMapping`, `@GetMapping`)
- Accept/return JSON via `@RequestBody` / return types
- Translate HTTP status codes (200, 400, 500)

**Should not do:**
- Virtual-thread logic
- HTTP calls to the target system under test
- Heavy computation

```text
HTTP Request  →  Controller  →  Service  →  Controller  →  JSON Response
```

**Implemented:** `POST /api/load-tests/run` on `LoadTestController`.

### Services (`services/`)

**Job:** Own the load-test workflow — fan-out, per-task execution, fan-in, result aggregation.

**Should do:**
- Run the virtual-thread engine
- Coordinate the list-driven fan-out loop, latch, semaphore, and result collection
- Call outbound HTTP via `RequestExecutor` (injected)
- **Validate target responses** — size limits and status codes before building `TestResponse` (see [Response Validation](./response-validation.md))
- **Apply failure policies** — optional early abort when failures spike ([Failure Policies](./failure-policies.md)) *(planned)*

**Should not do:**
- Parse raw HTTP request details (headers, query params) — that belongs in the controller
- Know about Spring MVC annotations

**Implemented today:**
- One virtual thread per payload + `CountDownLatch(totalTasks)` + `AtomicReferenceArray` fan-in
- `Semaphore(concurrencyLimit)` for max in-flight
- Non-2xx and body > 64 KB → `FAILED`
- Strategy: `RequestExecutor` / `HttpRequestExecutor`

### Model (`model/`)

**Job:** Data shapes only — records, enums, simple validation in compact constructors.

**Examples:**
- `LoadTestRequest` — payloads, concurrency, target URI (validates non-empty payloads, concurrency ≥ 1, non-null URI)
- `LoadTestResponse` — results + success/failure counts (counts must match array length)
- `Task`, `TestResponse` — internal execution units

**Should not do:**
- Call services or controllers
- Contain workflow logic

Keep models as **immutable records** where possible (`public record ...`).

### Config (`config/`)

**Job:** Spring `@Configuration` classes — beans like `HttpClient`, property binding.

**Implemented:** `HttpClientConfig` registers one thread-safe HTTP/2 `HttpClient` bean (10s connect timeout, no redirects). Inject it into `HttpRequestExecutor`; do not use `ThreadLocal`. Use `ScopedValue` only for lightweight run metadata on deep call stacks ([Enterprise Scale](./enterprise-scale.md)).

Not part of classic MVC, but standard in Spring Boot projects.

---

## Dependency rules

```text
controller  →  services  →  model
            →  services/port (interfaces)
config      →  wires beans (HttpClient → HttpRequestExecutor → LoadTestService)
```

| From | Can import | Cannot import |
|------|------------|---------------|
| `controller` | `services`, `model` | — |
| `services` | `model`, `services/port` | `controller` |
| `model` | JDK only | `services`, `controller`, Spring |

**Rule of thumb:** dependencies point downward. Models never depend on services; services never depend on controllers.

---

## Build status

| Step | Status |
|------|--------|
| Model — `LoadTestRequest`, `LoadTestResponse`, `Task`, `TestResponse` | **Done** |
| Services — Loom-idiomatic engine + basic response checks | **Done** |
| Config — shared `HttpClient` bean | **Done** |
| Controller — `POST /api/load-tests/run` | **Done** |
| Tests — service + controller | **Done** |
| Pluggable `ResponseValidator`, failure policies, pacing, Claim Check | Planned |

Web support is already on the classpath via `spring-boot-starter-web`.

---

## API

```http
POST /api/load-tests/run
Content-Type: application/json

{
  "payloads": ["{\"event\":\"ping\"}"],
  "concurrencyLimit": 10,
  "targetUri": "https://api.target-system.internal/v1/ingest"
}
```

```json
{
  "responses": [
    { "taskId": 0, "status": "SUCCESS", "responseBody": "..." }
  ],
  "successCount": 1,
  "failureCount": 0
}
```

`LoadTestRequest` rejects empty payloads, `concurrencyLimit < 1`, or a null `targetUri` with `IllegalArgumentException`.

---

## When to refactor

Stay on MVC until one of these happens:

| Signal | Action |
|--------|--------|
| `LoadTestService` exceeds ~200 lines | Extract engine into `services/ScaleTestingEngine` |
| Multiple transport types (HTTP, gRPC, Kafka) | Add more `RequestExecutor` implementations (Strategy — interface already exists) |
| Scenario definitions get complex | Add `model/scenario/` or a dedicated builder |
| You need persistence for run history | Add a `repository/` package for storage |

`RequestExecutor` already supports swap-in transports without rewriting the fan-out loop.
