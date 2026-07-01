# Design Patterns for Scale Testing Platform

Patterns worth coding into this project, ordered by **when to add them**. Each section explains what it is, where it fits in MVC, and what you gain.

Implement only what you need today. Over-patterning early slows you down.

---

## Priority overview

| Priority | Pattern | Where in MVC | Add when… |
|----------|---------|--------------|-----------|
| 1 | Producer–Consumer | `service/` | You build the task queue + worker loop |
| 2 | Command | `model/` + `service/` | A load test run is a single executable unit |
| 3 | Strategy | `service/` | You support more than one transport (HTTP, mock, gRPC) |
| 4 | Builder | `model/` | Scenario/request construction gets many optional fields |
| 5 | Claim Check | `model/` + `service/` | Payloads are large; queues carry tokens only |
| 6 | Template Method | `service/` | Multiple test types share the same run lifecycle |
| 7 | Observer | `service/` + `controller/` | You want live progress or metrics streaming |
| 8 | Factory | `service/` or `config/` | Worker/executor creation gets conditional |
| 9 | Adapter | `service/` | You wrap `java.net.http` or a third-party client |
| 10 | Facade | `service/` | Subsystems grow and callers need one simple entry point |

---

## 1. Producer–Consumer

**What:** Producers enqueue work; consumers (workers) dequeue and process it independently.

**Where:** `LoadTestService` — main thread feeds `Task` objects into a `BlockingQueue`; virtual-thread workers poll from it.

**Why for this project:** Decouples task submission from execution rate. The bounded queue gives natural backpressure (matches your README).

**Key types:**
- Producer: loop that `put()` tasks after fan-out starts
- Consumer: worker loop that `poll()` tasks until empty
- Buffer: `LinkedBlockingQueue<Task>`

**Tip:** Use a timeout on `poll()` so workers can exit when the queue is drained.

---

## 2. Command

**What:** Encapsulate a request as an object with everything needed to execute it.

**Where:**
- `model/LoadTestCommand.java` (or reuse `LoadTestRequest`) — payloads, concurrency, target URI
- `service/LoadTestService.run(command)` — executes without caring where the command came from

**Why for this project:** Same run logic whether triggered by REST, CLI, or a scheduled job later. Easy to log, replay, or queue commands.

**Example shape:**

```java
public record LoadTestCommand(
    List<String> payloads,
    int concurrencyLimit,
    URI targetUri
) {}
```

**Tip:** Validate in the record compact constructor (non-null payloads, concurrency ≥ 1).

---

## 3. Strategy

**What:** Define a family of algorithms behind one interface; swap implementations at runtime.

**Where:**
- Interface: `RequestExecutor` or `OutboundClient` in `service/` (or `service/port/`)
- Implementations: `HttpRequestExecutor`, `MockRequestExecutor`, later `GrpcRequestExecutor`

**Why for this project:** Unit-test the engine without real network I/O. Add protocols without rewriting the worker loop.

```text
LoadTestService
    └── uses RequestExecutor (interface)
            ├── HttpRequestExecutor
            └── MockRequestExecutor  (tests)
```

**Tip:** Inject the strategy via constructor — Spring `@Service` + `@Bean` makes this natural.

---

## 4. Builder

**What:** Construct complex objects step-by-step instead of telescoping constructors.

**Where:** `model/LoadScenarioBuilder.java` when requests gain optional fields:

- ramp-up duration
- think time between requests
- headers / auth
- payload file path vs inline body

**Why for this project:** Load-test configs get verbose. A builder keeps construction readable:

```java
LoadTestRequest request = LoadTestRequest.builder()
    .targetUri(uri)
    .concurrency(100)
    .payloadFromFile("scenarios/ingest.jsonl")
    .header("Authorization", "Bearer ...")
    .build();
```

**Tip:** Use a builder when you have **3+ optional parameters**. Until then, a record is enough.

---

## 5. Claim Check

**What:** Store heavy data elsewhere; pass a lightweight reference (ticket/token) through the pipeline.

**Where:**
- `model/PayloadRef.java` — ID or path instead of raw bytes
- `service/` — workers resolve the ref to actual payload before sending

**Why for this project:** Your README calls this out explicitly. Keeps the queue and fan-in array lightweight; avoids GC pressure from large objects in memory.

```text
Queue carries:  PayloadRef("file:///data/image-001.bin")
Worker resolves:  byte[] body = payloadStore.read(ref)
Sends:  HTTP POST with body
```

**Tip:** Start with inline string payloads; introduce Claim Check when you test with real binary/media files.

---

## 6. Template Method

**What:** Define the skeleton of an algorithm in a base class; subclasses override specific steps.

**Where:** Abstract `LoadTestRunner` in `service/` with steps:

1. `prepare()` — validate, build queue
2. `executeTask(Task)` — send one request (override per protocol)
3. `collect()` — fan-in results
4. `report()` — build response

**Why for this project:** Useful when you add test types that share fan-out/fan-in but differ in the per-task step (HTTP vs ping vs custom).

**Alternative in Java:** Prefer a **functional interface** + composition over inheritance unless you truly have a class hierarchy.

---

## 7. Observer

**What:** Subject notifies listeners when state changes; listeners react without the subject knowing details.

**Where:**
- `service/LoadTestProgressListener` — `onTaskCompleted`, `onRunFinished`
- Optional SSE endpoint in `controller/` for live dashboards

**Why for this project:** Long-running tests need progress. Observers keep metrics/logging out of the hot worker loop.

```text
ScaleTestingEngine  →  notifies  →  MetricsListener
                                   →  LoggingListener
                                   →  SseProgressController (future)
```

**Tip:** Use `CopyOnWriteArrayList` for listener lists if workers register at runtime, or inject a single `MetricsCollector` bean to keep it simple.

---

## 8. Factory

**What:** Centralize object creation so callers do not `new` concrete types directly.

**Where:**
- `config/ExecutorFactory` — create virtual-thread executor with consistent settings
- `config/HttpClientFactory` — shared timeouts, HTTP/2, redirect policy

**Why for this project:** One place to tune concurrency infrastructure. Tests can substitute a factory that returns a direct executor.

**Tip:** Spring `@Bean` methods in `config/` are already a factory — use explicit factory classes only when creation logic is non-trivial.

---

## 9. Adapter

**What:** Wrap an existing class with an interface your code expects.

**Where:** `HttpClientAdapter implements RequestExecutor` — wraps `java.net.http.HttpClient` behind your Strategy interface.

**Why for this project:** Keeps `LoadTestService` free of `HttpRequest`/`HttpResponse` details. Swap the adapter if you change HTTP libraries.

**Relation to Strategy:** Strategy is the *role*; Adapter is often how you *implement* it around a third-party API.

---

## 10. Facade

**What:** Provide a simple interface over a complex subsystem.

**Where:** `LoadTestService` itself can act as a facade over engine + HTTP + metrics.

**Why for this project:** Controllers stay thin — one call:

```java
loadTestService.run(request);
```

instead of orchestrating queue, latch, executor, and client directly.

**Tip:** If `LoadTestService` grows too large, extract `ScaleTestingEngine` internally but keep the facade as the public API for controllers.

---

## Concurrency patterns (not GoF, but essential here)

These are not classic Gang-of-Four patterns, but they are core to your architecture:

### Fan-out / Fan-in

- **Fan-out:** Start N workers (virtual threads) that consume from a shared queue
- **Fan-in:** `CountDownLatch` or similar barrier; gather results from `AtomicReferenceArray` by task index

Documented in your README — implement this in `service/` first.

### Lock-free index assignment

Each task gets a unique index; workers write only to `results[taskIndex]`. No locks on the result array.

---

## Patterns to skip for now

| Pattern | Why skip early |
|---------|----------------|
| **Singleton** | Spring manages bean scope — do not hand-roll singletons |
| **Abstract Factory** | Overkill until you have families of related objects |
| **Decorator** | Useful for middleware (retry, logging) later, not needed initially |
| **Repository (JPA-style)** | No database yet; add when you persist run history |
| **MVC variants (MVP, MVVM)** | REST JSON does not need them |

---

## Suggested implementation roadmap

```text
Phase 1 — Core loop
  Producer–Consumer + Fan-out/Fan-in + Command
  (all inside LoadTestService + model records)

Phase 2 — Testability
  Strategy + Adapter (HttpClient behind RequestExecutor)
  Mock executor in unit tests

Phase 3 — Real workloads
  Claim Check for large payloads
  Builder for richer scenario config

Phase 4 — Operations
  Observer for progress/metrics
  Optional SSE or WebSocket in controller
```

---

## Quick reference: pattern → package

```text
controller/     Command arrives as LoadTestRequest (HTTP JSON)
service/        Producer–Consumer, Fan-out/Fan-in, Strategy, Facade
model/          Command, Claim Check refs, Builder products
config/         Factory beans (HttpClient, Executor)
```

Code these yourself at your own pace — use this doc as a checklist, not a spec you must fulfill completely.
