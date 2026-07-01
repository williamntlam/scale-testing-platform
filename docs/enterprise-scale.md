# Enterprise Scale & Loom Idioms

This document captures architectural traps, JVM edge cases, and missing links that appear when moving from a **working prototype** to **millions of requests at sustained throughput**. Use it alongside the README blueprint and when implementing `LoadTestService`.

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

**Fix (Loom-idiomatic):**

```text
For each payload:
  executor.submit(() -> execute one task → write results[taskId] → countDown latch)

Main thread:
  enqueue all submissions (optionally paced)
  await latch (totalTasks)
```

Concurrency is controlled by **how many tasks you submit** and/or a **semaphore / rate limiter**, not by a fixed number of polling workers.

---

### No `BlockingQueue` — list-driven execution

In a traditional multi-threaded system, a **`BlockingQueue`** buffers tasks because physical worker threads are scarce — producers enqueue faster than consumers can dequeue.

**With Project Loom, you can remove the queue entirely.**

Virtual threads are cheap enough that the **data collection itself drives the loop**. The main execution thread walks a flat `List<String>` (from `LoadTestRequest.payloads()`) and submits one virtual thread per index:

```text
Traditional (platform threads):     Loom (target):
─────────────────────────────       ─────────────────────────────
Producer → BlockingQueue → Workers  for (i = 0; i < n; i++)
           (buffer)                      executor.submit(() -> run task i)
```

| | `BlockingQueue` model | List + one VT per item |
|---|----------------------|-------------------------|
| **Why it existed** | Few OS threads; queue decouples produce/consume rates | N/A at Loom scale |
| **Memory** | Queue holds pending `Task` objects | Only the source `List` + in-flight VT stacks |
| **Complexity** | Poll loops, timeouts, worker lifecycle races | Simple `for` loop + `submit` |
| **Backpressure** | Bounded queue blocks producer | `Semaphore`, pacing, or batched submission |

**Backpressure without a queue:** use `Semaphore(concurrencyLimit)` before `submit`, a token-bucket pace before each submit, or submit in batches for millions of tasks — do not reintroduce a queue unless you have a specific streaming producer with no upfront list.

The `Task` record remains useful as an **internal concept** (id + payload ref) but you do not need a queue to hand them off — bind id from the loop index and pass payload ref into the lambda directly.

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

Ensure **Logback** and **Log4j2** are on versions whose appenders and core paths use **`ReentrantLock`** (or other non-pinning primitives) instead of **`synchronized`** on I/O-heavy code paths. Spring Boot 3.x / 4.x parent POMs generally pull recent versions — verify after upgrades.

| Action | Detail |
|--------|--------|
| Use current Logback / Log4j2 | Let Spring Boot dependency management manage versions where possible |
| Prefer **async appenders** | Keeps formatting off the request hot path (`AsyncAppender`, Log4j2 `Async`) |
| Reduce hot-path logging | Avoid `log.debug` inside tight loops at millions of RPS — even async has cost |

**2. Detect pinning instantly during testing**

Run the JVM with pinning tracing enabled in **dev, CI, and pre-production load tests**:

```bash
java -Djdk.tracePinnedThreads=full -jar target/scale-testing-platform-*.jar
```

When a virtual thread pins, the JVM prints a **stack trace to stderr** showing exactly which `synchronized` block or native call caused it. Fix or upgrade those call sites before claiming “zero context-switch overhead.”

| Flag | Purpose |
|------|---------|
| `-Djdk.tracePinnedThreads=full` | Log stack trace on every pin (verbose; use in testing) |
| `-Djdk.tracePinnedThreads=short` | Shorter pin messages (optional middle ground) |

**3. Other mitigations**

- Use `java.net.http.HttpClient` (Loom-friendly for HTTP).
- Keep logging out of the innermost per-request loop where possible.
- Re-run a short load test with tracing after **every dependency upgrade** (logging, drivers, HTTP clients).

**Pre-flight:** A clean run with `-Djdk.tracePinnedThreads=full` under representative load is a **gate** before benchmark numbers are trusted.

---

### The solution: explicit dependency injection vs. `ScopedValue`

> **Loom gold standard:** Make virtual threads **entirely stateless**. Each task body should only use **local variables** plus references to **shared, immutable context** (injected `HttpClient`, captured `URI`, `ScopedValue`-bound run metadata). No per-VT mutable fields, no `ThreadLocal`, no hidden thread-owned state.

To inject context and dependencies into millions of virtual threads **without copying data**, you have two primary approaches. For the **core scale engine**, prefer explicit injection; reserve `ScopedValue` for lightweight run metadata on deep call stacks.

**What “stateless virtual thread” means in practice:**

| Virtual thread holds | Shared immutable context |
|---------------------|---------------------------|
| Local `taskId`, payload ref, response scratch | `HttpClient`, `RequestExecutor` (injected once) |
| Nothing after the lambda returns | `RunContext` via `ScopedValue` (run id, read-only flags) |
| ❌ Instance fields on worker objects | ❌ `ThreadLocal` parsers, clients, buffers |

**Do not use `ThreadLocal`** — on platform-thread pools it reused expensive objects “per worker.” With millions of virtual threads that model breaks down:

- A `ThreadLocal` is **per thread** — one slot per virtual thread → **massive memory overhead** if each VT gets its own parser, buffer, or client.
- Virtual threads mount/unmount on **carrier** threads; `ThreadLocal` does not give you a cheap “pool of 8 heavy objects for 8 carriers” without custom carrier-scoped pooling.
- Libraries that stash state in `ThreadLocal` can behave poorly or leak under extreme VT counts.

---

#### 1. The cleanest way: explicit parameter injection (core engine)

For your **core scale engine**, the **highest-performance pattern** is:

1. Instantiate heavy, **thread-safe** dependencies (`HttpClient`, `RequestExecutor`, `ResponseValidator`) **once** at the application root (`config/` beans or `LoadTestService` constructor).
2. Pass them as **immutable references** into each virtual thread’s execution block — constructor injection, method parameters, or **lambda capture**.

No implicit lookup. No per-thread slots. One shared object, millions of readers.

```java
// config/HttpClientConfig.java — created once at application root
@Bean
HttpClient httpClient() {
    return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
}

// service/LoadTestService.java — injected once, passed into every VT
@Service
public class LoadTestService {

    private final HttpClient httpClient;
    private final RequestExecutor requestExecutor;

    public LoadTestService(HttpClient httpClient, RequestExecutor requestExecutor) {
        this.httpClient = httpClient;
        this.requestExecutor = requestExecutor;
    }

    public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
        List<String> payloads = request.payloads();
        AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(payloads.size());
        CountDownLatch done = new CountDownLatch(payloads.size());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < payloads.size(); i++) {
                final int taskId = i;
                final String payloadRef = payloads.get(i);
                final URI targetUri = request.targetUri(); // immutable capture

                // Explicit capture — httpClient & requestExecutor are shared, not copied
                executor.submit(() -> {
                    try {
                        String body = resolvePayload(payloadRef);
                        String reply = requestExecutor.send(httpClient, targetUri, body);
                        results.set(taskId, new TestResponse(taskId, TestStatus.SUCCESS, reply));
                    }
                    catch (Exception e) {
                        results.set(taskId, new TestResponse(taskId, TestStatus.FAILED, e.getMessage()));
                    }
                    finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        }
        return aggregate(results);
    }
}
```

| Dependency | Pattern |
|------------|---------|
| `HttpClient` | Single `@Bean`; capture in lambda |
| `RequestExecutor`, `ResponseValidator` | Constructor inject into `@Service` |
| `ObjectMapper` (if needed) | Single immutable instance; pass explicitly |
| Per-task data (`taskId`, `payloadRef`) | `final` locals captured by lambda |
| Per-task mutable buffers | Local variables inside the lambda body only |

This is **explicit sharing**: one client, many virtual threads, HTTP/2 connection reuse — not carrier pooling magic.

**Why this wins for throughput:** zero indirection on the hot path, no `ThreadLocal.get()`, no scope lookup, no duplicate heavy objects — just a captured reference already on the stack when the VT runs.

---

#### 2. `ScopedValue` — implicit immutable context (secondary)

When **lightweight run metadata** must flow down a **deep call stack** (trace id, run id, read-only flags) and parameter drilling becomes noisy, use **`ScopedValue`** — **not** for `HttpClient` or parsers:

```java
private static final ScopedValue<RunContext> RUN = ScopedValue.newInstance();

public LoadTestResponse run(LoadTestRequest request, HttpClient httpClient) {
    RunContext ctx = new RunContext(request.targetUri(), runId);
    return ScopedValue.where(RUN, ctx).call(() -> executeAllTasks(request, httpClient));
}

// Deep in the stack — no parameter drilling, no ThreadLocal
private void validateResponse(String body) {
    URI target = RUN.get().targetUri();
    // ...
}
```

**Why `ScopedValue` is ideal for Project Loom:**

| | `ThreadLocal` | `ScopedValue` |
|---|---------------|---------------|
| **Memory model** | One copy (or slot) **per thread** — millions of virtual threads → millions of slots or duplicated heavy objects | **Zero data duplication** — child virtual threads read from a **single shared immutable** value bound to the parent execution scope |
| **Lifecycle** | Lives until manually `remove()`’d or the thread dies — easy to **leak** in thread pools and under VT churn | **Automatic cleanup** — invisible and eligible for GC the moment the bounding `ScopedValue.where(...).call(...)` block closes |
| **Loom fit** | Designed for scarce platform threads; breaks down at VT scale | Designed for **structured, scoped** context across mounted/unmounted virtual threads |

**Zero data duplication:** Instead of each virtual thread creating or inheriting its own copy of run metadata, every child VT in the scope reads the **same** immutable `RunContext` (or trace id, target URI snapshot) bound when the run starts. One object, millions of readers — no per-thread heap slot.

**Automatic garbage collection:** `ThreadLocal` requires discipline — call `.remove()` in `finally` blocks or risk **permanent memory leaks** when threads are pooled or recycled. With `ScopedValue`, the binding is tied to a **lexical scope**. When `ScopedValue.where(RUN, ctx).call(...)` returns, the binding is gone; the context record is collected like any other unreachable object. No manual cleanup in worker `finally` blocks.

Additional properties:

- Designed for **immutable** context bound to a **dynamic scope** (a block of execution), not “one bag per thread forever.”
- Works cleanly when virtual threads unmount/remount on carriers — context travels with the **scope**, not the carrier thread.
- Use for **small immutable records** (`RunContext`, correlation id) — not for `HttpClient` or parsers (pass those explicitly).

**When to use which:**

| Need | Use |
|------|-----|
| **`HttpClient`, executors, validators (core engine)** | **Explicit parameter injection / lambda capture** — highest performance |
| Run-scoped metadata on deep stacks (run id, trace id) | `ScopedValue` |
| Mutable per-task buffers | Local variables in the task body |
| Legacy “one parser per thread” | Refactor to shared immutable parser; pass explicitly |

#### Anti-patterns to avoid

```java
// BAD — new client per task (port exhaustion)
executor.submit(() -> HttpClient.newHttpClient().send(...));

// BAD — ThreadLocal parser under millions of VTs
private static final ThreadLocal<ObjectMapper> MAPPER = ThreadLocal.withInitial(ObjectMapper::new);

// GOOD — shared client, explicit capture
executor.submit(() -> send(httpClient, uri, body));
```

---

## 2. Structural blind spots

### Coordinated pacing (rate limiting)

**Problem:** Max-out execution fires requests as fast as the NIC allows — useful for peak stress, useless for “exactly 5,000 RPS steady state.”

**What's needed:** A **token bucket** or **paced scheduler** before task submission:

```text
for each task:
  rateLimiter.acquire()   // blocks virtual thread cheaply until token available
  executor.submit(...)
```

Implementation sketch: `AtomicLong` next permit time, or Guava `RateLimiter`, or a simple lock-free bucket with `LongAdder` refill. Place in `service/` — `RateLimiter` / `PacingStrategy` interface (Strategy pattern).

Config example:

```yaml
scale-testing:
  pacing:
    target-rps: 5000        # 0 = unlimited (max-out)
```

---

### Claim Check — the memory leak paradox

Your architecture says heavy payloads (raw images, binary fragments) should flow as **lightweight tokens**, not bulk heap objects. Two common mistakes break this — **both** cause failure at scale:

#### Failure mode A: token sent as the HTTP body

If `payloads.get(i)` is a **token** (file path, S3 key, claim-check id) but the virtual thread does:

```java
HttpRequest.BodyPublishers.ofString(metadata)
```

you POST the **token string** to the target API (`"file:///data/img-001.bin"`), not the image bytes. The API receives garbage; the test is invalid.

#### Failure mode B: raw data in the list — no real Claim Check

If `metadata` **is** the full payload (multi-MB JSON, base64 image, etc.), you are **not** using Claim Check. You stored millions of heavy strings in `LoadTestRequest.payloads()` (or historically a `LinkedBlockingQueue`). Result:

```text
millions of large String / byte[] on heap  →  OOM + GC heap fragmentation
```

Even without a queue, **the list itself** becomes the memory bomb if entries are full payloads.

| What you pass | What happens |
|---------------|--------------|
| Token + `ofString(token)` | Wrong bytes on the wire |
| Full payload in list | OOM / GC collapse — not Claim Check |

#### What's missing: worker-level resolution

The **list holds lightweight reference tokens only**. Inside the virtual thread execution block, the worker **resolves** the token and **streams** heavy bytes directly into the HTTP client — disk, object store, or off-heap `ByteBuffer` — **without** loading megabytes into the heap first.

```text
List / LoadTestRequest     →  "claim://payload-001"     (small token)
Virtual thread (at send)  →  payloadStore.open(ref)     (stream / mmap / ByteBuffer)
HttpClient                →  BodyPublishers.ofFile(path) or custom BodyPublisher
Target API                →  receives actual bytes
```

**Correct patterns:**

```java
// Small inline JSON (v1 — fine for smoke tests)
BodyPublishers.ofString("{\"event\":\"ping\"}")

// Claim Check — stream from disk (heap stays small)
Path path = payloadStore.resolvePath(payloadRef);
BodyPublishers.ofFile(path)

// Claim Check — stream from store / off-heap (advanced)
BodyPublisher publisher = payloadStore.openStreamingBody(payloadRef);
// reads in chunks; never materialize full byte[] for large assets
```

**Implementation pieces (Phase 5):**

| Piece | Role |
|-------|------|
| `model/PayloadRef` | Typed token (path, key, id) — optional; `String` ok early |
| `service/PayloadStore` | Interface: `resolvePath`, `openStream`, `openBodyPublisher` |
| `service/FilePayloadStore` | Local file adapter |
| Virtual thread lambda | Resolve **at send time**; stream to `HttpClient` |

```java
executor.submit(() -> {
    // Resolve HERE — not when building LoadTestRequest, not in the list
    HttpRequest.BodyPublisher body = payloadStore.bodyPublisher(payloadRef);
    HttpRequest request = HttpRequest.newBuilder()
            .uri(targetUri)
            .POST(body)
            .build();
    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
});
```

**Phase guidance:**

| Phase | Payloads |
|-------|----------|
| **Step 2** (now) | Small inline JSON strings in list — acceptable for learning |
| **Step 5** | Tokens in list + `PayloadStore` + streaming body publishers |
| **Never at scale** | Multi-MB strings in `payloads` or `ofString(token)` for file refs |

Add `service/PayloadStore` (interface) + `FilePayloadStore` adapter when you move beyond inline JSON strings. See [Design Patterns](./design-patterns.md) § Claim Check.

---

### Real-time metrics vs final report

**Problem:** `AtomicReferenceArray` is ideal for **per-task final results** indexed by id. It is **not** ideal for live RPS, P99 latency, or error histograms — scanning the array under load is expensive.

**What's needed:**

| Concern | Structure |
|---------|-----------|
| Global success/failure | `LongAdder` |
| Current RPS | `LongAdder` + time window or striped counters |
| Latency samples | Bounded **ring buffer** (MPMC) consumed by telemetry thread |
| Final per-task report | `AtomicReferenceArray<TestResponse>` (keep this) |

Optional: Observer / SSE controller reads from ring buffer without touching workers (see [Design Patterns](./design-patterns.md)).

---

## 3. Operational details (host, JVM, OS)

These sit **outside** your Java concurrency model but will invalidate results or break runs at millions of requests if ignored.

### DNS resolution caching

**Problem:** The JVM caches DNS lookups (`InetAddress`). Default TTL can be long (or “forever” in some security-manager configs). During a load test against a hostname behind a load balancer, **all traffic may pin to a single resolved IP** — you are not testing the real fleet, and you can overload one backend node.

**Fix:**

- Set JVM network cache properties for load-test runs:

```bash
java \
  -Dnetworkaddress.cache.ttl=0 \
  -Dnetworkaddress.cache.negative.ttl=0 \
  -jar target/scale-testing-platform-*.jar
```

| Property | Effect |
|----------|--------|
| `networkaddress.cache.ttl=0` | Re-query DNS when cache entry expires (0 = always refresh on lookup semantics — verify in your JDK docs) |
| `networkaddress.cache.negative.ttl=0` | Do not long-cache failed lookups |

**Platform options:**

- Document required JVM flags in runbooks / README
- Optional `config/JvmDnsProperties` note in `application.yaml` comments (flags must be set at JVM launch, not Spring properties)
- For lab tests against a **single** IP, caching may be fine — make behavior **configurable** per profile (`smoke` vs `distributed-target`)

**Caution:** `ttl=0` increases DNS traffic; acceptable for controlled load tests, not necessarily for 24/7 production clients.

---

### OS ephemeral port exhaustion

**Problem:** Each new TCP connection consumes an **ephemeral outbound port** on the load-generator host. At millions of rapid short-lived connections, the OS runs out of ports or accumulates sockets in `TIME_WAIT` — throughput collapses even if the JVM is perfect.

Symptoms:

- `ConnectException`, `Cannot assign requested address`, rising error rate under max-out
- `ss -s` / `netstat` showing huge `TIME_WAIT` counts

**Fixes (combine where possible):**

| Approach | What to do |
|----------|------------|
| **HTTP/2 multiplexing** | Share one `HttpClient` bean; many requests over **few connections** |
| **Connection reuse** | Do not create a new `HttpClient` per request; keep-alive enabled |
| **Limit new connections** | Semaphore on in-flight + reuse pools |
| **OS tuning** (load-gen host) | Widen ephemeral range (`ip_local_port_range`), tune `tcp_tw_reuse` / `tcp_fin_timeout` per your OS policy |
| **Target-side** | Ensure server supports HTTP/2 if you rely on multiplexing |

`HttpClient` HTTP/2 (default when server supports it):

```java
HttpClient.newBuilder()
    .version(HttpClient.Version.HTTP_2)  // prefer HTTP/2
    .connectTimeout(Duration.ofSeconds(10))
    .build();
```

**Architecture:** Inject a **singleton** `HttpClient` in `config/HttpClientConfig.java` — never `newHttpClient()` inside the per-task loop.

Document OS tuning in runbooks; do not hard-code sysctl changes in application code.

---

### JIT warm-up cycles

**Problem:** The first N thousand requests run on **interpreted / C1-compiled** bytecode. Latency and throughput during that phase are **not representative** of steady-state performance. Publishing benchmark numbers without warm-up is misleading.

**Fix:** Run a **throwaway warm-up phase** before the measured run:

```text
Phase 1 — Warm-up (discarded)
  ~10,000 requests (or until latency stabilizes)
  Same code paths: HTTP, serialization, validation, metrics

Phase 2 — Measured run
  Record metrics only from this phase
  Return LoadTestResponse from measured phase only
```

**Implementation sketch** in `LoadTestService`:

```java
if (request.warmupRequests() > 0) {
    runWarmup(request, httpClient); // results discarded; may use subset of payloads cyclically
}
return runMeasured(request, httpClient);
```

Config example:

```yaml
scale-testing:
  warmup:
    request-count: 10000      # 0 = skip
    use-same-target: true
```

**Tips:**

- Warm-up should hit the **same** `targetUri`, payload shape, and headers as the real test
- Optionally wait for JIT settle: stop warm-up when rolling P99 variance drops below a threshold (advanced)
- Log warm-up duration separately; never mix warm-up samples into `LoadTestResponse`

---

## 4. Recommended engine shape (refactored)

Aligns with Project Loom, Claim Check, pacing, and safe fan-in:

```java
public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
    List<String> payloads = request.payloads();
    int totalTasks = payloads.size();

    AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(totalTasks);
    CountDownLatch done = new CountDownLatch(totalTasks);
    RunMetrics metrics = new RunMetrics(); // LongAdder + optional latency ring

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < totalTasks; i++) {
            final int taskId = i;
            final String payloadRef = payloads.get(i);

            pacingStrategy.acquire(); // no-op if unlimited

            executor.submit(() -> {
                long start = System.nanoTime();
                try {
                    String body = payloadStore.resolve(payloadRef); // or stream via publisher
                    String reply = requestExecutor.send(request.targetUri(), body);
                    // validate + truncate reply (see response-validation.md)
                    results.set(taskId, new TestResponse(taskId, TestStatus.SUCCESS, reply));
                    metrics.recordSuccess(System.nanoTime() - start);
                }
                catch (Exception e) {
                    results.set(taskId, new TestResponse(taskId, TestStatus.FAILED, e.getMessage()));
                    metrics.recordFailure(System.nanoTime() - start);
                }
                finally {
                    done.countDown();
                }
            });
        }
        done.await();
    }

    return aggregate(results, metrics);
}
```

**Concurrency control options:**

| Mode | How |
|------|-----|
| Max-out | Submit all tasks immediately (millions → consider batching submission) |
| Fixed parallelism | `Semaphore(concurrencyLimit)` before `executor.submit` |
| Target RPS | `pacingStrategy.acquire()` per submission |

---

## 5. Implementation roadmap (scale features)

| Phase | Feature | Doc |
|-------|---------|-----|
| **Step 2** | One virtual thread per task + `CountDownLatch(totalTasks)` | This doc §1 |
| **Step 2** | Shared singleton `HttpClient` (connection reuse) | This doc §3 |
| **Step 2** | Response size caps | [Response Validation](./response-validation.md) |
| **Step 3** | `RequestExecutor` Strategy + mock adapter; shared `HttpClient` bean | [Design Patterns](./design-patterns.md) |
| **Step 3** | Explicit dependency pass-in; no `ThreadLocal` for clients/parsers | This doc §1 |
| **Step 4** | Pacing / token bucket | This doc §2 |
| **Step 5** | Claim Check + `PayloadStore` | This doc §2 |
| **Step 6** | `LongAdder` metrics + ring buffer telemetry | This doc §2 |
| **Step 7** | JIT warm-up phase (discarded) before measured run | This doc §3 |
| **Step 8** | Pinning detection in CI / local profile | This doc §1 |
| **Step 9** | Failure policies / circuit breaker | [Failure Policies](./failure-policies.md) |
| **Ops** | JVM DNS cache flags + OS port tuning runbook | This doc §3 |

---

## 6. Pre-flight checklist

Use before claiming a run is “enterprise ready”:

- [ ] **Loom model** — one virtual thread per task; latch = `totalTasks`
- [ ] **Pinning** — `-Djdk.tracePinnedThreads=full` clean on a short run
- [ ] **DNS** — `networkaddress.cache.ttl=0` when target is a load-balanced hostname
- [ ] **Connections** — singleton `HttpClient`; HTTP/2 where supported; no per-request client
- [ ] **Stateless VTs** — task lambdas use locals + shared immutable refs only; no `ThreadLocal` or per-VT mutable state
- [ ] **No ThreadLocal** — heavy objects injected or passed explicitly; `ScopedValue` only for immutable run context
- [ ] **Ports** — monitor `TIME_WAIT`; OS ephemeral range documented if max-out
- [ ] **Warm-up** — 10k (or configured) throwaway requests before measured phase
- [ ] **Pacing** — target RPS set if steady-state required
- [ ] **No BlockingQueue** — list-driven fan-out; one virtual thread per payload index
- [ ] **Claim Check** — tokens in list; resolve and stream at send; large bodies not held on heap
- [ ] **Responses** — body size capped; suspicious content flagged
- [ ] **Failure policy** — smoke vs stress abort rules configured

---

## 7. What the original README blueprint was

The original **fixed worker pool + `LinkedBlockingQueue` + `fanInGate(concurrencyLimit)`** pattern reflected **platform-thread scarcity** — a queue buffered work because you could not afford one thread per task. That design is **deprecated** for this project.

Keep the lessons:

- `AtomicReferenceArray` for lock-free per-index writes
- Virtual threads for blocking I/O
- Claim Check — lightweight references, heavy data resolved at send

Replace entirely:

- **`BlockingQueue` / `Task` queue handoff** → iterate `List` directly
- **Fixed polling workers** → one virtual thread per list index
- **Latch per worker count** → `CountDownLatch(totalTasks)`
- **Resolve payloads at send time** for Claim Check

---

## Related docs

- [MVC Structure](./mvc-structure.md) — where engine code lives (`service/`)
- [Design Patterns](./design-patterns.md) — Strategy, Claim Check, Observer
- [Response Validation](./response-validation.md)
- [Failure Policies](./failure-policies.md)
