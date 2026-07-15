# Enterprise Scale & Loom Idioms

This document captures architectural traps, JVM edge cases, and missing links that appear when moving from a **working prototype** to **millions of requests at sustained throughput**. Use it alongside the README and when extending `LoadTestService`.

---

## Current engine (baseline)

Already in place:

| Goal | Mechanism | Status |
|------|-----------|--------|
| Avoid OS thread-pool thinking on Loom | One virtual thread per task | **Done** |
| Avoid lock contention on results | Pre-sized `AtomicReferenceArray` indexed by task id | **Done** |
| No task buffer required | List-driven fan-out — no `BlockingQueue` | **Done** |
| Cap concurrent in-flight work | `Semaphore(concurrencyLimit)` | **Done** |
| Shared client / connection reuse | Singleton HTTP/2 `HttpClient` bean | **Done** |
| Transport Strategy | `RequestExecutor` + `HttpRequestExecutor` | **Done** |
| Response body safety | 64 KB cap + non-2xx → `FAILED` | **Done** (basic) |
| Avoid heap pressure on large payloads | Claim Check — tokens in list; stream at send | Planned |
| Simulate steady load | Token-bucket / target RPS | Planned |
| Live telemetry without blocking workers | `LongAdder` + lock-free ring buffer | Planned |
| Detect carrier pinning | `-Djdk.tracePinnedThreads=full` in test runs | Ops practice |
| Load-balance across target IPs | JVM DNS cache TTL | Ops practice |
| Stable benchmark numbers | JIT warm-up cycle before measured run | Planned |
| Loom gold standard | Stateless VTs + shared immutable context | **Mostly done** (explicit injection) |
| Implicit run metadata on deep stacks | `ScopedValue` | Planned if needed |

---

## Design goals at scale

| Goal | Mechanism |
|------|-----------|
| Avoid OS thread-pool thinking on Loom | One virtual thread per task (not fixed worker pool + poll loop) |
| Avoid lock contention on results | Pre-sized `AtomicReferenceArray` indexed by task id |
| Avoid heap pressure on large payloads | Claim Check — **list** holds tokens; VTs stream from disk/off-heap at send time |
| No task buffer required | **List-driven fan-out** — one virtual thread per item; no `BlockingQueue` |
| Simulate steady load | Token-bucket or coordinated pacing (target RPS) |
| Live telemetry without blocking workers | `LongAdder` counters + lock-free ring buffer for latencies |
| Detect carrier pinning | `-Djdk.tracePinnedThreads=full` in test runs |
| Load-balance across target IPs | JVM DNS cache TTL (`networkaddress.cache.ttl=0`) |
| Avoid ephemeral port exhaustion | HTTP/2 multiplexing + shared `HttpClient` / connection reuse |
| Stable benchmark numbers | JIT warm-up cycle before measured run |
| Loom gold standard | **Stateless virtual threads** + **shared immutable context** (explicit injection / `ScopedValue`) |
| Share heavy clients without heap bloat | **Explicit parameter injection** at app root — not `ThreadLocal` |
| Implicit run metadata on deep stacks | `ScopedValue` (Java 21+) — not for `HttpClient` / parsers |

---

## 1. Critical flaws under heavy load

### The fixed worker pool + queue anti-pattern

**Problem:** Spawning `concurrencyLimit` long-lived virtual-thread workers that `poll()` a shared queue mimics old `ThreadPoolExecutor` design. Combined with `try-with-resources` on `ExecutorService`:

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // submit N worker loops that poll until null
    // feed queue
    fanInGate.await();
} // close() → shutdown + await termination
```

Risks:

1. **Race on empty queue** — workers may `poll()` timeout, see `null`, and exit before all tasks are enqueued.
2. **Double lifecycle management** — `fanInGate.await()` plus `executor.close()` both wait for completion; easy to get ordering wrong.
3. **Wrong abstraction for Loom** — virtual threads are cheap; use **one virtual thread per task**, not a fixed pool of pollers.

**Fix (Loom-idiomatic) — this is what the codebase does:**

```text
For each payload:
  semaphore.acquire()
  executor.submit(() -> execute one task → write results[taskId] → release → countDown)

Main thread:
  await latch (totalTasks)
```

Concurrency is controlled by a **semaphore** and/or a **rate limiter**, not by a fixed number of polling workers.

---

### No `BlockingQueue` — list-driven execution

In a traditional multi-threaded system, a **`BlockingQueue`** buffers tasks because physical worker threads are scarce — producers enqueue faster than consumers can dequeue.

**With Project Loom, you can remove the queue entirely.**

Virtual threads are cheap enough that the **data collection itself drives the loop**. The main execution thread walks a flat `List<String>` (from `LoadTestRequest.payloads()`) and submits one virtual thread per index:

```text
Traditional (platform threads):     Loom (implemented):
─────────────────────────────       ─────────────────────────────
Producer → BlockingQueue → Workers  for (i = 0; i < n; i++)
           (buffer)                      semaphore.acquire()
                                         executor.submit(() -> run task i)
```

| | `BlockingQueue` model | List + one VT per item |
|---|----------------------|-------------------------|
| **Why it existed** | Few OS threads; queue decouples produce/consume rates | N/A at Loom scale |
| **Memory** | Queue holds pending `Task` objects | Only the source `List` + in-flight VT stacks |
| **Complexity** | Poll loops, timeouts, worker lifecycle races | Simple `for` loop + `submit` |
| **Backpressure** | Bounded queue blocks producer | `Semaphore`, pacing, or batched submission |

**Backpressure without a queue:** use `Semaphore(concurrencyLimit)` before `submit` (**done**), a token-bucket pace before each submit (**planned**), or submit in batches for millions of tasks — do not reintroduce a queue unless you have a specific streaming producer with no upfront list.

The `Task` record remains useful as an **internal concept** (id + payload ref) but the engine does not enqueue it — bind id from the loop index and pass the payload into the lambda directly.

---

### Virtual thread pinning

**Problem:** If code on the virtual thread holds a `synchronized` block during blocking work (I/O, logging, legacy libraries), the virtual thread **pins** to its carrier OS thread. The carrier cannot run other virtual threads until the block completes. Throughput collapses to traditional thread-pool behavior — your Loom architecture stops working.

Common pinning sources in load testers:

| Source | Why it pins |
|--------|-------------|
| **`synchronized` in libraries** | Pins carrier for duration of the block |
| **Older logging (Logback / Log4j2)** | Appender or layout code paths still using `synchronized` |
| **JDBC drivers, file I/O helpers** | Legacy synchronized streams |
| **Your own code** | `synchronized` methods on the hot path |

#### The fix

**1. Update logging frameworks**

Ensure **Logback** and **Log4j2** are on versions whose appenders and core paths use **`ReentrantLock`** (or other non-pinning primitives) instead of **`synchronized`** on I/O-heavy code paths. Spring Boot 4.x parent POMs generally pull recent versions — verify after upgrades.

| Action | Detail |
|--------|--------|
| Use current Logback / Log4j2 | Let Spring Boot dependency management manage versions where possible |
| Prefer **async appenders** | Keeps formatting off the request hot path (`AsyncAppender`, Log4j2 `Async`) |
| Reduce hot-path logging | Avoid `log.debug` inside tight loops at millions of RPS — even async has cost |

**2. Detect pinning instantly during testing**

```bash
java -Djdk.tracePinnedThreads=full -jar target/scale-testing-platform-*.jar
```

When a virtual thread pins, the JVM prints a **stack trace to stderr** showing exactly which `synchronized` block or native call caused it.

| Flag | Purpose |
|------|---------|
| `-Djdk.tracePinnedThreads=full` | Log stack trace on every pin (verbose; use in testing) |
| `-Djdk.tracePinnedThreads=short` | Shorter pin messages (optional middle ground) |

**3. Other mitigations**

- Use `java.net.http.HttpClient` (already the outbound transport via `HttpRequestExecutor`).
- Keep logging out of the innermost per-request loop where possible.
- Re-run a short load test with tracing after **every dependency upgrade**.

**Pre-flight:** A clean run with `-Djdk.tracePinnedThreads=full` under representative load is a **gate** before benchmark numbers are trusted.

---

### The solution: explicit dependency injection vs. `ScopedValue`

> **Loom gold standard:** Make virtual threads **entirely stateless**. Each task body should only use **local variables** plus references to **shared, immutable context** (injected `HttpClient`, captured `URI`, `ScopedValue`-bound run metadata). No per-VT mutable fields, no `ThreadLocal`, no hidden thread-owned state.

The core engine already follows **explicit parameter injection**:

1. `HttpClient` is a singleton `@Bean` in `config/HttpClientConfig`.
2. `HttpRequestExecutor` receives it via constructor; `LoadTestService` receives `RequestExecutor` via constructor.
3. Virtual-thread lambdas capture task locals (`taskId`, `payload`) and call the shared executor — no `ThreadLocal`.

#### 1. Explicit parameter injection (implemented)

```java
// config/HttpClientConfig.java — created once at application root
@Bean
HttpClient httpClient() {
    return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
}

// services/LoadTestService.java — injected once, used by every VT
@Service
public class LoadTestService {

    private final RequestExecutor requestExecutor;

    public LoadTestService(RequestExecutor requestExecutor) {
        this.requestExecutor = requestExecutor;
    }

    public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
        // list-driven fan-out + semaphore + AtomicReferenceArray — see source
    }
}
```

| Dependency | Pattern | Status |
|------------|---------|--------|
| `HttpClient` | Single `@Bean` | **Done** |
| `RequestExecutor` | Constructor inject into `@Service` | **Done** |
| Per-task data (`taskId`, `payload`) | `final` locals captured by lambda | **Done** |
| `ResponseValidator` | Constructor inject | Planned |
| `ObjectMapper` (if needed) | Single immutable instance; pass explicitly | N/A today |

#### 2. `ScopedValue` — implicit immutable context (planned if needed)

When **lightweight run metadata** must flow down a **deep call stack** (trace id, run id, read-only flags) and parameter drilling becomes noisy, use **`ScopedValue`** — **not** for `HttpClient` or parsers.

**Do not use `ThreadLocal`** for heavy objects under millions of virtual threads — one slot per VT → heap bloat and leak risk without `.remove()`.

| Need | Use |
|------|-----|
| **`HttpClient`, executors, validators (core engine)** | **Explicit parameter injection / lambda capture** |
| Run-scoped metadata on deep stacks (run id, trace id) | `ScopedValue` |
| Mutable per-task buffers | Local variables in the task body |

#### Anti-patterns to avoid

```java
// BAD — new client per task (port exhaustion)
executor.submit(() -> HttpClient.newHttpClient().send(...));

// BAD — ThreadLocal parser under millions of VTs
private static final ThreadLocal<ObjectMapper> MAPPER = ThreadLocal.withInitial(ObjectMapper::new);

// GOOD — shared client behind RequestExecutor (current code)
executor.submit(() -> requestExecutor.send(targetUri, payload));
```

---

## 2. Structural blind spots

### Coordinated pacing (rate limiting) — planned

**Problem:** Max-out execution fires requests as fast as the NIC (and semaphore) allow — useful for peak stress, useless for “exactly 5,000 RPS steady state.”

**What's needed:** A **token bucket** or **paced scheduler** before task submission:

```text
for each task:
  rateLimiter.acquire()   // blocks virtual thread cheaply until token available
  inFlight.acquire()
  executor.submit(...)
```

Implementation sketch: `AtomicLong` next permit time, Guava `RateLimiter`, or a simple lock-free bucket. Place in `services/` — `RateLimiter` / `PacingStrategy` interface (Strategy pattern).

```yaml
scale-testing:
  pacing:
    target-rps: 5000        # 0 = unlimited (max-out)
```

Today only `concurrencyLimit` (semaphore) is available.

---

### Claim Check — the memory leak paradox — planned

Your architecture says heavy payloads (raw images, binary fragments) should flow as **lightweight tokens**, not bulk heap objects. Two common mistakes break this — **both** cause failure at scale:

#### Failure mode A: token sent as the HTTP body

If `payloads.get(i)` is a **token** (file path, S3 key, claim-check id) but the virtual thread does:

```java
HttpRequest.BodyPublishers.ofString(metadata)
```

you POST the **token string** to the target API (`"file:///data/img-001.bin"`), not the image bytes.

#### Failure mode B: raw data in the list — no real Claim Check

If entries **are** full multi-MB payloads, the list itself becomes the memory bomb.

| What you pass | What happens |
|---------------|--------------|
| Token + `ofString(token)` | Wrong bytes on the wire |
| Full payload in list | OOM / GC collapse — not Claim Check |

#### What's missing: worker-level resolution

```text
List / LoadTestRequest     →  "claim://payload-001"     (small token)
Virtual thread (at send)  →  payloadStore.open(ref)     (stream / mmap / ByteBuffer)
HttpClient                →  BodyPublishers.ofFile(path) or custom BodyPublisher
Target API                →  receives actual bytes
```

**Phase guidance:**

| Phase | Payloads |
|-------|----------|
| **Now** | Small inline JSON strings in list — acceptable for learning / smoke |
| **Later** | Tokens in list + `PayloadStore` + streaming body publishers |
| **Never at scale** | Multi-MB strings in `payloads` or `ofString(token)` for file refs |

Add `services/PayloadStore` (interface) + `FilePayloadStore` adapter when you move beyond inline JSON. See [Design Patterns](./design-patterns.md) § Claim Check.

---

### Real-time metrics vs final report — planned

**Problem:** `AtomicReferenceArray` is ideal for **per-task final results** indexed by id. It is **not** ideal for live RPS, P99 latency, or error histograms — scanning the array under load is expensive.

**What's needed:**

| Concern | Structure | Status |
|---------|-----------|--------|
| Final success/failure counts | Aggregated after latch | **Done** |
| Live RPS / latency | `LongAdder` + ring buffer | Planned |
| Final per-task report | `AtomicReferenceArray<TestResponse>` | **Done** |

Optional: Observer / SSE controller reads from ring buffer without touching workers (see [Design Patterns](./design-patterns.md)).

---

## 3. Operational details (host, JVM, OS)

These sit **outside** your Java concurrency model but will invalidate results or break runs at millions of requests if ignored.

### DNS resolution caching

**Problem:** The JVM caches DNS lookups. During a load test against a hostname behind a load balancer, **all traffic may pin to a single resolved IP**.

**Fix:**

```bash
java \
  -Dnetworkaddress.cache.ttl=0 \
  -Dnetworkaddress.cache.negative.ttl=0 \
  -jar target/scale-testing-platform-*.jar
```

| Property | Effect |
|----------|--------|
| `networkaddress.cache.ttl=0` | Re-query DNS when cache entry expires (verify semantics in your JDK docs) |
| `networkaddress.cache.negative.ttl=0` | Do not long-cache failed lookups |

**Caution:** `ttl=0` increases DNS traffic; acceptable for controlled load tests, not necessarily for 24/7 production clients.

---

### OS ephemeral port exhaustion

**Problem:** Each new TCP connection consumes an **ephemeral outbound port**. At millions of rapid short-lived connections, the OS runs out of ports or accumulates sockets in `TIME_WAIT`.

**Fixes (combine where possible):**

| Approach | What to do | Status |
|----------|------------|--------|
| **HTTP/2 multiplexing** | Share one `HttpClient` bean; many requests over few connections | **Done** (client configured for HTTP/2) |
| **Connection reuse** | Do not create a new `HttpClient` per request | **Done** |
| **Limit new connections** | Semaphore on in-flight | **Done** |
| **OS tuning** (load-gen host) | Widen ephemeral range; tune `TIME_WAIT` per OS policy | Runbook |

`HttpClientConfig` already prefers HTTP/2 with a 10s connect timeout and never follows redirects.

---

### JIT warm-up cycles — planned

**Problem:** The first N thousand requests run on **interpreted / C1-compiled** bytecode. Latency and throughput during that phase are **not representative**.

**Fix:** Run a **throwaway warm-up phase** before the measured run:

```text
Phase 1 — Warm-up (discarded)
  ~10,000 requests (or until latency stabilizes)

Phase 2 — Measured run
  Record metrics only from this phase
```

```yaml
scale-testing:
  warmup:
    request-count: 10000      # 0 = skip
    use-same-target: true
```

Warm-up should hit the **same** `targetUri` and payload shape as the real test. Never mix warm-up samples into `LoadTestResponse`.

---

## 4. Recommended engine shape (aligns with current code + next features)

```java
public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
    List<String> payloads = request.payloads();
    int totalTasks = payloads.size();

    AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(totalTasks);
    CountDownLatch done = new CountDownLatch(totalTasks);
    Semaphore inFlight = new Semaphore(request.concurrencyLimit());
    // RunMetrics metrics = new RunMetrics(); // planned: LongAdder + latency ring

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < totalTasks; i++) {
            final int taskId = i;
            final String payloadRef = payloads.get(i);

            // pacingStrategy.acquire(); // planned
            inFlight.acquire();

            executor.submit(() -> {
                try {
                    // payloadStore.resolve / stream — planned for Claim Check
                    TestResponse result = executeTask(taskId, request.targetUri(), payloadRef);
                    results.set(taskId, result);
                } catch (Exception e) {
                    results.set(taskId, new TestResponse(taskId, TestStatus.FAILED, e.getMessage()));
                } finally {
                    inFlight.release();
                    done.countDown();
                }
            });
        }
        done.await();
    }

    return aggregate(results);
}
```

**Concurrency control options:**

| Mode | How | Status |
|------|-----|--------|
| Max-out | High `concurrencyLimit` | Possible today |
| Fixed parallelism | `Semaphore(concurrencyLimit)` | **Done** |
| Target RPS | `pacingStrategy.acquire()` per submission | Planned |

---

## 5. Implementation roadmap (scale features)

| Phase | Feature | Status | Doc |
|-------|---------|--------|-----|
| **Step 2** | One VT per task + `CountDownLatch(totalTasks)` | **Done** | This doc §1 |
| **Step 2** | Shared singleton `HttpClient` (HTTP/2) | **Done** | This doc §3 |
| **Step 2** | Response size caps + non-2xx | **Done** (basic) | [Response Validation](./response-validation.md) |
| **Step 3** | `RequestExecutor` Strategy + adapter | **Done** | [Design Patterns](./design-patterns.md) |
| **Step 3** | Explicit dependency pass-in; no `ThreadLocal` | **Done** | This doc §1 |
| **Step 3+** | Extract `ResponseValidator`; content-type / suspicious checks | Planned | [Response Validation](./response-validation.md) |
| **Step 4** | Pacing / token bucket | Planned | This doc §2 |
| **Step 5** | Claim Check + `PayloadStore` | Planned | This doc §2 |
| **Step 6** | `LongAdder` metrics + ring buffer telemetry | Planned | This doc §2 |
| **Step 7** | JIT warm-up phase (discarded) before measured run | Planned | This doc §3 |
| **Step 8** | Pinning detection in CI / local profile | Ops | This doc §1 |
| **Step 9** | Failure policies / circuit breaker | Planned | [Failure Policies](./failure-policies.md) |
| **Ops** | JVM DNS cache flags + OS port tuning runbook | Ops | This doc §3 |

---

## 6. Pre-flight checklist

Use before claiming a run is “enterprise ready”:

- [x] **Loom model** — one virtual thread per task; latch = `totalTasks`
- [ ] **Pinning** — `-Djdk.tracePinnedThreads=full` clean on a short run
- [ ] **DNS** — `networkaddress.cache.ttl=0` when target is a load-balanced hostname
- [x] **Connections** — singleton `HttpClient`; HTTP/2 where supported; no per-request client
- [x] **Stateless VTs** — task lambdas use locals + shared immutable refs only; no `ThreadLocal`
- [ ] **Ports** — monitor `TIME_WAIT`; OS ephemeral range documented if max-out
- [ ] **Warm-up** — 10k (or configured) throwaway requests before measured phase
- [ ] **Pacing** — target RPS set if steady-state required
- [x] **No BlockingQueue** — list-driven fan-out; one virtual thread per payload index
- [ ] **Claim Check** — tokens in list; resolve and stream at send
- [x] **Responses** — body size capped; non-2xx failed *(suspicious content still planned)*
- [ ] **Failure policy** — smoke vs stress abort rules configured

---

## 7. What the original README blueprint was

The original **fixed worker pool + `LinkedBlockingQueue` + `fanInGate(concurrencyLimit)`** pattern reflected **platform-thread scarcity**. That design is **deprecated** for this project and is **not** what the code does.

Keep the lessons:

- `AtomicReferenceArray` for lock-free per-index writes
- Virtual threads for blocking I/O
- Claim Check — lightweight references, heavy data resolved at send

Replace entirely (done in code for the queue/pool side):

- **`BlockingQueue` / `Task` queue handoff** → iterate `List` directly
- **Fixed polling workers** → one virtual thread per list index
- **Latch per worker count** → `CountDownLatch(totalTasks)`
- **Resolve payloads at send time** for Claim Check *(still planned)*

---

## Related docs

- [MVC Structure](./mvc-structure.md) — where engine code lives (`services/`)
- [Design Patterns](./design-patterns.md) — Strategy, Claim Check, Observer
- [Response Validation](./response-validation.md)
- [Failure Policies](./failure-policies.md)
