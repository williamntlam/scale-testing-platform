# Design Patterns for Scale Testing Platform

Patterns worth coding into this project, ordered by **when to add them**. Each section explains what it is, where it fits in MVC, and what you gain.

Implement only what you need today. Over-patterning early slows you down.

---

## Priority overview

| Priority | Pattern | Where in MVC | Add when… |
|----------|---------|--------------|-----------|
| 1 | List-driven fan-out | `service/` | You implement the core load loop (one VT per payload) |
| 2 | Command | `model/` + `service/` | A load test run is a single executable unit |
| 3 | Strategy | `service/` | You support more than one transport (HTTP, mock, gRPC) or response validator |
| 4 | Builder | `model/` | Scenario/request construction gets many optional fields |
| 5 | Claim Check | `model/` + `service/` | Payloads are large; **list** carries tokens only |
| 6 | Template Method | `service/` | Multiple test types share the same run lifecycle |
| 7 | Observer | `service/` + `controller/` | You want live progress or metrics streaming |
| 8 | Factory | `service/` or `config/` | Worker/executor creation gets conditional |
| 9 | Adapter | `service/` | You wrap `java.net.http` or a third-party client |
| 10 | Facade | `service/` | Subsystems grow and callers need one simple entry point |
| 11 | Circuit Breaker | `service/` | Repeated failures — stop hammering a dead target ([Failure Policies](./failure-policies.md)) |

---

## 1. List-driven fan-out (no `BlockingQueue`)

**What:** The main thread iterates a flat `List` (or stream) of payload references and submits **one virtual thread per item**. The data drives execution — no intermediate task queue.

**Where:** `LoadTestService.run()` — loop over `request.payloads()`, `executor.submit(...)` per index.

**Why for this project:** `BlockingQueue` exists to buffer work when OS threads are scarce. Virtual threads remove that constraint. A queue adds races (`poll` timeouts), memory overhead, and platform-thread thinking ([Enterprise Scale](./enterprise-scale.md)).

```text
for (int i = 0; i < payloads.size(); i++) {
    final int taskId = i;
    final String ref = payloads.get(i);
    pacingStrategy.acquire();           // optional
    semaphore.acquire();                // optional max in-flight
    executor.submit(() -> runTask(taskId, ref));
}
done.await();
```

**Backpressure without a queue:**

| Need | Use |
|------|-----|
| Max in-flight requests | `Semaphore(concurrencyLimit)` before `submit` |
| Steady RPS | Token bucket / `pacingStrategy.acquire()` before `submit` |
| Millions of tasks | Batch submission (submit chunk, await partial latch, repeat) |

**Do not use:** `LinkedBlockingQueue<Task>`, worker loops with `poll()`, or `fanInGate(concurrencyLimit)`.

**Optional:** Keep the `Task` record as a mental model (id + payload ref), but construct it inline in the loop — nothing enqueues it.

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

**Also use for:** `ResponseValidator` — pluggable checks for response size, content-type, and suspicious bodies ([Response Validation](./response-validation.md)).

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

**What:** Store heavy data elsewhere; pass a lightweight reference (ticket/token) through the pipeline. Resolve and **stream** at send time in the virtual thread — never bulk-load megabytes into the list or heap.

**Where:**
- `model/PayloadRef.java` — ID or path instead of raw bytes (optional; `String` token ok early)
- `service/PayloadStore.java` — resolves token → `Path`, `InputStream`, or `BodyPublisher`
- Virtual thread lambda — **worker-level resolution** immediately before HTTP POST

**The memory leak paradox** — two ways Claim Check goes wrong:

| Mistake | Result |
|---------|--------|
| Token in list + `BodyPublishers.ofString(token)` | API receives `"file:///…"`, not file bytes |
| Full payload strings in list | Not Claim Check — OOM / GC fragmentation at millions of tasks |

**Correct flow:**

```text
List carries:    PayloadRef("file:///data/image-001.bin")   // lightweight
VT resolves:     payloadStore.bodyPublisher(ref)            // stream at send
HTTP:            BodyPublishers.ofFile(path) or chunked BodyPublisher
Target:          receives actual bytes; heap stays pristine
```

```java
// BAD — sends the token, not the file
BodyPublishers.ofString(payloadRef);

// BAD — megabytes in heap per list entry
payloads.add(entireImageAsBase64String);

// GOOD — resolve in the VT, stream from disk
BodyPublishers.ofFile(payloadStore.resolvePath(payloadRef));
```

**Tip:** Step 2 — small inline JSON strings are fine. Introduce `PayloadStore` + streaming when you test real binary/media at scale ([Enterprise Scale](./enterprise-scale.md) § Claim Check paradox).

---

## 6. Template Method

**What:** Define the skeleton of an algorithm in a base class; subclasses override specific steps.

**Where:** Abstract `LoadTestRunner` in `service/` with steps:

1. `prepare()` — validate request, size results array
2. `executeTask(taskId, payloadRef)` — send one request (override per protocol)
3. `collect()` — fan-in via latch
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

**Tip:** Spring `@Bean` methods in `config/` are already a factory — use explicit singleton beans for `HttpClient`. Do **not** use `ThreadLocal` to reuse clients across virtual threads; see [Enterprise Scale](./enterprise-scale.md).

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

instead of orchestrating latch, executor, and client directly.

**Tip:** If `LoadTestService` grows too large, extract `ScaleTestingEngine` internally but keep the facade as the public API for controllers.

---

## 11. Circuit Breaker

**What:** After enough failures, stop sending new requests so you do not waste resources or overwhelm a failing target.

**Where:** `service/FailureMonitor` or `service/CircuitBreaker` — workers check `shouldAbort()` before HTTP; record success/failure after each task.

**Why for this project:** A smoke test should fail fast; a stress test may run to completion. Same engine, different **failure policy** ([Failure Policies](./failure-policies.md)).

```text
CLOSED  →  send requests normally
OPEN    →  stop submitting new tasks OR skip HTTP and mark FAILED instantly
```

**Tip:** Default to `RUN_TO_COMPLETION` for stress tests. Add `FAIL_FAST` or circuit breaker when you add smoke-test mode or production-safe profiles.

---

## Concurrency patterns (not GoF, but essential here)

These are not classic Gang-of-Four patterns, but they are core to your architecture:

### Fan-out / Fan-in

- **Fan-out:** Submit one virtual thread per task (or paced batches at millions of tasks)
- **Fan-in:** `CountDownLatch(totalTasks)`; gather results from `AtomicReferenceArray` by task index

See [Enterprise Scale](./enterprise-scale.md) for why fixed worker pools + `poll()` are an anti-pattern on Loom.

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
  List-driven fan-out (one VT per payload) + Fan-in + Command
  (LoadTestService + model records — see Enterprise Scale)

Phase 2 — Testability
  Strategy + Adapter (HttpClient behind RequestExecutor)
  Mock executor in unit tests
  ResponseValidator — byte limits + suspicious content checks

Phase 3 — Real workloads
  Claim Check: PayloadStore resolves tokens; stream bodies at send time
  Pacing: token bucket / target RPS
  Builder for richer scenario config

Phase 4 — Operations
  LongAdder + latency ring buffer for live metrics
  Observer for progress/metrics
  Optional SSE or WebSocket in controller
  Failure policies + circuit breaker (FAIL_FAST for smoke, RUN_TO_COMPLETION for stress)
  Pinning detection: -Djdk.tracePinnedThreads=full
  JVM DNS cache: networkaddress.cache.ttl=0 for load-balanced targets
  Shared HttpClient + HTTP/2; OS ephemeral port runbook
  JIT warm-up: ~10k discarded requests before measured run
```

---

## Quick reference: pattern → package

```text
controller/     Command arrives as LoadTestRequest (HTTP JSON)
service/        List-driven fan-out, Fan-in, Strategy, Facade
model/          Command, Claim Check refs, Builder products
config/         Factory beans (HttpClient, Executor), response limits
```

See also: [Response Validation](./response-validation.md), [Enterprise Scale](./enterprise-scale.md).

Code these yourself at your own pace — use this doc as a checklist, not a spec you must fulfill completely.
