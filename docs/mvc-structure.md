# MVC Project Structure

This project uses **Spring MVC** (REST API) with a simple three-layer layout. Keep it flat until a folder grows uncomfortable — you can split later.

---

## Recommended layout

```text
src/main/java/io/github/williamntlam/scale_testing_platform/
├── ScaleTestingPlatformApplication.java
│
├── controller/          # MVC — C (Controller)
│   └── LoadTestController.java
│
├── service/             # MVC — business logic & orchestration
│   └── LoadTestService.java
│
├── model/               # MVC — M (Model)
│   ├── LoadTestRequest.java
│   ├── LoadTestResponse.java
│   ├── Task.java
│   └── EngineResponse.java
│
└── config/              # Spring wiring (not a fourth MVC layer)
    └── HttpClientConfig.java

src/test/java/.../       # Mirror the packages above
├── controller/
├── service/
└── ScaleTestingPlatformApplicationTests.java
```

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

### Service (`service/`)

**Job:** Own the load-test workflow — fan-out, worker loop, fan-in, result aggregation.

**Should do:**
- Run the virtual-thread engine
- Coordinate queues, latches, and result collection
- Call outbound HTTP (or delegate to a helper you inject)

**Should not do:**
- Parse raw HTTP request details (headers, query params) — that belongs in the controller
- Know about Spring MVC annotations

This is where most of your README blueprint lives.

### Model (`model/`)

**Job:** Data shapes only — records, enums, simple validation in compact constructors.

**Examples:**
- `LoadTestRequest` — payloads, concurrency, target URI
- `LoadTestResponse` — results + success/failure counts
- `Task`, `EngineResponse` — internal execution units

**Should not do:**
- Call services or controllers
- Contain workflow logic

Keep models as **immutable records** where possible (`public record ...`).

### Config (`config/`)

**Job:** Spring `@Configuration` classes — beans like `HttpClient`, property binding.

Not part of classic MVC, but standard in Spring Boot projects.

---

## Dependency rules

```text
controller  →  service  →  model
config      →  wires beans (service, HttpClient)
```

| From | Can import | Cannot import |
|------|------------|---------------|
| `controller` | `service`, `model` | — |
| `service` | `model` | `controller` |
| `model` | JDK only | `service`, `controller`, Spring |

**Rule of thumb:** dependencies point downward. Models never depend on services; services never depend on controllers.

---

## Suggested build order (for you to implement)

1. **Model** — define `LoadTestRequest`, `LoadTestResponse`, `Task`, `EngineResponse`
2. **Service** — implement the virtual-thread engine from the README
3. **Config** — register a shared `HttpClient` bean
4. **Controller** — expose `POST /api/load-tests/run`
5. **Tests** — service unit tests first (fast, no Spring), then controller tests

---

## Maven dependency

Add web support when you start the controller:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

---

## API sketch

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
    { "taskIndex": 0, "status": "SUCCESS", "responseBody": "..." }
  ],
  "successCount": 1,
  "failureCount": 0
}
```

---

## When to refactor

Stay on MVC until one of these happens:

| Signal | Action |
|--------|--------|
| `LoadTestService` exceeds ~200 lines | Extract engine into `service/ScaleTestingEngine` |
| Multiple transport types (HTTP, gRPC, Kafka) | Introduce a `RequestExecutor` interface (Strategy pattern) |
| Scenario definitions get complex | Add `model/scenario/` or a dedicated builder |
| You need persistence for run history | Add a `repository/` package for storage |

You do not need those layers on day one.
