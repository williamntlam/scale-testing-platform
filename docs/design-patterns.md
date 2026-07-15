# Design Patterns for Scale Testing Platform

Patterns worth coding into this project, ordered by **when to add them**. Each section explains what it is, where it fits in MVC, and what you gain.

Implement only what you need today. Over-patterning early slows you down.

---

## Priority overview

| Priority | Pattern | Where in MVC | Status |
|----------|---------|--------------|--------|
| 1 | List-driven fan-out | `services/` | **Done** — one VT per payload |
| 2 | Command | `model/` + `services/` | **Done** — `LoadTestRequest` as the executable unit |
| 3 | Strategy | `services/port/` | **Done** — `RequestExecutor` + `HttpRequestExecutor` |
| 4 | Adapter | `services/` | **Done** — `HttpRequestExecutor` wraps `java.net.http` |
| 5 | Facade | `services/` | **Done** — `LoadTestService.run(request)` |
| 6 | Factory | `config/` | **Done** — `@Bean HttpClient` in `HttpClientConfig` |
| 7 | Builder | `model/` | Planned — when requests gain many optional fields |
| 8 | Claim Check | `model/` + `services/` | Planned — list carries tokens only |
| 9 | Template Method | `services/` | Planned — multiple test types sharing lifecycle |
| 10 | Observer | `services/` + `controller/` | Planned — live progress / metrics |
| 11 | Circuit Breaker | `services/` | Planned — repeated failures ([Failure Policies](./failure-policies.md)) |
| — | ResponseValidator (Strategy) | `services/` | Planned — `ValidatedResponse` record already exists |

---

## 1. List-driven fan-out (no `BlockingQueue`) — done

**What:** The main thread iterates a flat `List` of payload references and submits **one virtual thread per item**. The data drives execution — no intermediate task queue.

**Where:** `LoadTestService.run()` — loop over `request.payloads()`, `inFlight.acquire()`, then `executor.submit(...)` per index.

**Why for this project:** `BlockingQueue` exists to buffer work when OS threads are scarce. Virtual threads remove that constraint. A queue adds races (`poll` timeouts), memory overhead, and platform-thread thinking ([Enterprise Scale](./enterprise-scale.md)).

```text
for (int i = 0; i < payloads.size(); i++) {
    final int taskId = i;
    final String payload = payloads.get(i);
    inFlight.acquire();                 // max in-flight (done)
    // pacingStrategy.acquire();        // optional RPS (planned)
    executor.submit(() -> runTask(taskId, payload));
}
done.await();
```

**Backpressure without a queue:**

| Need | Use | Status |
|------|-----|--------|
| Max in-flight requests | `Semaphore(concurrencyLimit)` before `submit` | **Done** |
| Steady RPS | Token bucket / `pacingStrategy.acquire()` before `submit` | Planned |
| Millions of tasks | Batch submission (submit chunk, await partial latch, repeat) | Planned |

**Do not use:** `LinkedBlockingQueue<Task>`, worker loops with `poll()`, or `fanInGate(concurrencyLimit)`.

**Optional:** Keep the `Task` record as a mental model (id + payload ref), but the engine currently binds id from the loop index and passes the payload string into the lambda directly.

---

## 2. Command — done

**What:** Encapsulate a request as an object with everything needed to execute it.

**Where:**
- `model/LoadTestRequest.java` — payloads, concurrency, target URI
- `services/LoadTestService.run(request)` — executes without caring where the command came from

**Why for this project:** Same run logic whether triggered by REST, CLI, or a scheduled job later. Easy to log, replay, or queue commands.

```java
public record LoadTestRequest(
    List<String> payloads,
    int concurrencyLimit,
    URI targetUri
) {}
```

Validation lives in the record compact constructor (non-empty payloads, concurrency ≥ 1, non-null URI).

---

## 3. Strategy — done (transport); planned (validators)

**What:** Define a family of algorithms behind one interface; swap implementations at runtime.

**Where:**
- Interface: `services/port/RequestExecutor`
- Implementations: `HttpRequestExecutor` (production); mock executor in tests later

**Why for this project:** Unit-test the engine without real network I/O. Add protocols without rewriting the worker loop.

```text
LoadTestService
    └── uses RequestExecutor (interface)
            ├── HttpRequestExecutor
            └── MockRequestExecutor  (tests — optional)
```

Injected via constructor — Spring wires `HttpRequestExecutor` as a `@Component`.

**Also use for (planned):** `ResponseValidator` — pluggable checks for response size, content-type, and suspicious bodies. Basic size/status checks are currently **inline** in `LoadTestService.executeTask` ([Response Validation](./response-validation.md)).

---

## 4. Builder — planned

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

**Tip:** Use a builder when you have **3+ optional parameters**. Until then, the current record is enough.

---

## 5. Claim Check — planned

**What:** Store heavy data elsewhere; pass a lightweight reference (ticket/token) through the pipeline. Resolve and **stream** at send time in the virtual thread — never bulk-load megabytes into the list or heap.

**Where:**
- `model/PayloadRef.java` — ID or path instead of raw bytes (optional; `String` token ok early)
- `services/PayloadStore.java` — resolves token → `Path`, `InputStream`, or `BodyPublisher`
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

**Today:** small inline JSON strings are posted with `ofString` — fine for smoke tests. Introduce `PayloadStore` + streaming when you test real binary/media at scale ([Enterprise Scale](./enterprise-scale.md) § Claim Check paradox).

---

## 6. Template Method — planned

**What:** Define the skeleton of an algorithm in a base class; subclasses override specific steps.

**Where:** Abstract `LoadTestRunner` in `services/` with steps:

1. `prepare()` — validate request, size results array
2. `executeTask(taskId, payloadRef)` — send one request (override per protocol)
3. `collect()` — fan-in via latch
4. `report()` — build response

**Why for this project:** Useful when you add test types that share fan-out/fan-in but differ in the per-task step (HTTP vs ping vs custom).

**Alternative in Java:** Prefer a **functional interface** + composition over inheritance unless you truly have a class hierarchy. The current `RequestExecutor` Strategy already covers the per-task HTTP step.

---

## 7. Observer — planned

**What:** Subject notifies listeners when state changes; listeners react without the subject knowing details.

**Where:**
- `services/LoadTestProgressListener` — `onTaskCompleted`, `onRunFinished`
- Optional SSE endpoint in `controller/` for live dashboards

**Why for this project:** Long-running tests need progress. Observers keep metrics/logging out of the hot worker loop.

```text
ScaleTestingEngine  →  notifies  →  MetricsListener
                                   →  LoggingListener
                                   →  SseProgressController (future)
```

**Tip:** Use `CopyOnWriteArrayList` for listener lists if workers register at runtime, or inject a single `MetricsCollector` bean to keep it simple.

---

## 8. Factory — done

**What:** Centralize object creation so callers do not `new` concrete types directly.

**Where:** `config/HttpClientConfig` — shared HTTP/2 client, timeouts, redirect policy.

**Why for this project:** One place to tune concurrency infrastructure. Tests can substitute a different executor or client.

**Tip:** Spring `@Bean` methods in `config/` are already a factory. Do **not** use `ThreadLocal` to reuse clients across virtual threads; see [Enterprise Scale](./enterprise-scale.md).

---

## 9. Adapter — done

**What:** Wrap an existing class with an interface your code expects.

**Where:** `HttpRequestExecutor implements RequestExecutor` — wraps `java.net.http.HttpClient` behind the Strategy interface. Returns `OutboundResponse(statusCode, body)`.

**Why for this project:** Keeps `LoadTestService` free of `HttpRequest`/`HttpResponse` details. Swap the adapter if you change HTTP libraries.

**Relation to Strategy:** Strategy is the *role*; Adapter is often how you *implement* it around a third-party API.

---

## 10. Facade — done

**What:** Provide a simple interface over a complex subsystem.

**Where:** `LoadTestService` itself acts as a facade over the executor, latch, semaphore, and aggregation.

**Why for this project:** Controllers stay thin — one call:

```java
loadTestService.run(request);
```

**Tip:** If `LoadTestService` grows too large, extract `ScaleTestingEngine` internally but keep the facade as the public API for controllers.

---

## 11. Circuit Breaker — planned

**What:** After enough failures, stop sending new requests so you do not waste resources or overwhelm a failing target.

**Where:** `services/FailureMonitor` or `services/CircuitBreaker` — workers check `shouldAbort()` before HTTP; record success/failure after each task.

**Why for this project:** A smoke test should fail fast; a stress test may run to completion. Same engine, different **failure policy** ([Failure Policies](./failure-policies.md)).

```text
CLOSED  →  send requests normally
OPEN    →  stop submitting new tasks OR skip HTTP and mark FAILED instantly
```

**Today:** runs always process every payload (`RUN_TO_COMPLETION` behavior). Add `FAIL_FAST` or circuit breaker when you add smoke-test mode or production-safe profiles.

---

## Concurrency patterns (not GoF, but essential here)

### Fan-out / Fan-in — done

- **Fan-out:** Submit one virtual thread per task
- **Fan-in:** `CountDownLatch(totalTasks)`; gather results from `AtomicReferenceArray` by task index

See [Enterprise Scale](./enterprise-scale.md) for why fixed worker pools + `poll()` are an anti-pattern on Loom.

### Lock-free index assignment — done

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

## Implementation roadmap

```text
Phase 1 — Core loop                          ✅ DONE
  List-driven fan-out (one VT per payload) + Fan-in + Command
  Semaphore concurrency + shared HttpClient

Phase 2 — Testability                        ✅ mostly DONE
  Strategy + Adapter (HttpClient behind RequestExecutor)
  Inline response size/status checks
  Remaining: MockRequestExecutor in unit tests; ResponseValidator extraction

Phase 3 — Real workloads                     Planned
  Claim Check: PayloadStore resolves tokens; stream bodies at send time
  Pacing: token bucket / target RPS
  Builder for richer scenario config

Phase 4 — Operations                         Planned
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
controller/        Command arrives as LoadTestRequest (HTTP JSON)
services/          List-driven fan-out, Fan-in, Facade, Adapter
services/port/     Strategy interfaces (RequestExecutor) + DTOs
model/             Command, Claim Check refs (future), Builder products (future)
config/            Factory beans (HttpClient)
```

See also: [Response Validation](./response-validation.md), [Enterprise Scale](./enterprise-scale.md).

Use this doc as a checklist for what is left — core Strategy/Facade/fan-out pieces are already in the tree.
