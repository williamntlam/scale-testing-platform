# Scale Testing Platform

A lock-free, virtual-threaded benchmarking and traffic-generation engine built in Java 21. Designed to simulate massive enterprise workloads (high-volume media ingestion, financial transaction processing) with high throughput and minimal lock contention — when implemented with **Project Loom idioms**, not legacy thread-pool patterns.

---

## Architectural Philosophy

Traditional scale-testing frameworks rely on OS-level thread pools (`ThreadPoolExecutor`). Under heavy parallel I/O, thousands of platform threads block and the kernel spends CPU on context switches.

**Scale Testing Platform** shifts concurrency to the JVM using **Virtual Threads (Project Loom)** and **lock-free fan-in** (`AtomicReferenceArray` indexed by task id) to avoid contended locks on the result path.

Important nuance at enterprise scale:

- **Gold standard for Loom:** virtual threads should be **entirely stateless** — use **shared, immutable context** (explicit injection for `HttpClient`/executors; `ScopedValue` for lightweight run metadata). No `ThreadLocal`, no per-VT heavy state.
- Virtual threads excel when tasks are **short-lived and I/O-bound** — spawn **one virtual thread per request**, not a fixed pool of workers polling a queue.
- **Carrier pinning** (`synchronized` in libraries) silently destroys throughput — update **Logback/Log4j2** to versions that use `ReentrantLock` instead of `synchronized` where possible; detect pins with `-Djdk.tracePinnedThreads=full` during testing.
- **Heavy dependencies** (`HttpClient`, executors) — instantiate once at the app root; **pass explicitly** into each virtual thread (highest performance). Use `ScopedValue` only for lightweight run metadata on deep stacks.
- **Claim Check** means the **payload list** carries **lightweight tokens**; each virtual thread **resolves and streams** heavy data at send time — not `ofString(token)` on the wire.
- **No `BlockingQueue`** — with one virtual thread per request, iterate `List<String>` (or a stream) directly; the data drives the loop ([enterprise-scale.md](docs/enterprise-scale.md)).
- **Steady-state load** requires explicit **pacing** (token bucket / target RPS), not max-out submission alone.

See [docs/enterprise-scale.md](docs/enterprise-scale.md) for pitfalls, fixes, and rollout phases.

---

## Core Architecture Layers

### 1. User-Space Thread Multiplexing

Virtual threads map onto a small set of carrier threads. When a virtual thread blocks on Loom-friendly I/O (`java.net.http`), it unmounts and frees the carrier.

- Blocking HTTP → virtual thread yields (if not **pinned**)
- Carriers stay busy with other virtual threads
- **Anti-pattern:** long-lived “worker loops” that poll a queue — use **one virtual thread per task** instead (see blueprint below)

### 2. Lock-Free Fan-In (`AtomicReferenceArray`)

Each task has a unique index. Workers write only to `results[taskId]` — no lock on the result array.

- Pre-allocate `AtomicReferenceArray<TestResponse>` sized to task count
- Suitable for **final per-task reports**
- For **live RPS / P99**, add `LongAdder` and a latency ring buffer (not array scans) — [enterprise-scale.md](docs/enterprise-scale.md)

### 3. Claim Check Pipeline

Heavy assets stay off the hot execution path. The **list holds tokens only**; each virtual thread **resolves and streams** at send time.

**The paradox:** `BodyPublishers.ofString(token)` sends the path, not the file. Putting full payloads in the list causes OOM — that is not Claim Check. See [enterprise-scale.md](docs/enterprise-scale.md) § Claim Check memory leak paradox.

```text
List / payload ref   →  lightweight token (path, key, id)
Virtual thread       →  PayloadStore resolves → stream (file / ByteBuffer)
HttpClient           →  BodyPublishers.ofFile(path) or streaming BodyPublisher
Target API           →  receives bytes, not the token string
```

---

## Tech Stack & Prerequisites

- **Language:** Java 21+ (Virtual Threads / Loom)
- **Build:** Maven (`./mvnw`)
- **HTTP:** `java.net.http.HttpClient`
- **Concurrency:** `java.util.concurrent`, `java.util.concurrent.atomic`
- **App structure:** Spring Boot MVC — see [docs/mvc-structure.md](docs/mvc-structure.md)

---

## Target Implementation Blueprint (Loom-idiomatic)

This is the **recommended** engine shape for `LoadTestService`. The main thread walks the payload **list** and submits one virtual thread per item — **no `BlockingQueue`**.

```text
List<String> payloads  →  for each index i  →  executor.submit(task i)
                                              →  results[i] = TestResponse
                                              →  done.countDown()
```

```java
public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
    List<String> payloadRefs = request.payloads();
    int totalTasks = payloadRefs.size();

    AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(totalTasks);
    CountDownLatch done = new CountDownLatch(totalTasks);

    HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    // One virtual thread per task — not a fixed pool of polling workers
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < totalTasks; i++) {
            final int taskId = i;
            final String payloadRef = payloadRefs.get(i);

            // Future: pacingStrategy.acquire();  // target RPS
            // Future: payloadStore.openStream(payloadRef) for Claim Check

            executor.submit(() -> {
                try {
                    String body = resolvePayload(payloadRef); // token → bytes/string at send time
                    String reply = executeNetworkCall(httpClient, request.targetUri(), body);
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

    return aggregateResults(results);
}

private static String resolvePayload(String payloadRef) {
    // v1: inline JSON string
    // v2: read file / object store from token — stream, do not queue megabytes
    return payloadRef;
}

private static String executeNetworkCall(HttpClient client, URI targetUri, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
            .uri(targetUri)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
}
```

### Concurrency & pacing (planned)

| Mode | Mechanism |
|------|-----------|
| Unlimited (stress) | Submit all tasks; optional batching for millions of tasks |
| Max in-flight | `Semaphore(concurrencyLimit)` before submit |
| Steady RPS | Token bucket / `pacingStrategy.acquire()` per submit |

Configure via `LoadTestRequest` or `application.yaml` — see [docs/enterprise-scale.md](docs/enterprise-scale.md).

---

## Known pitfalls (summary)

| Issue | Symptom | Fix |
|-------|---------|-----|
| Fixed worker pool + `BlockingQueue` | Race conditions, premature worker exit | Iterate `List` directly; one VT per item |
| Fixed worker pool + `poll()` timeout | Workers exit early, lost tasks | One VT per task; latch = `totalTasks` |
| `try-with-resources` + wrong latch count | Hang or double shutdown | Latch per **task**, not per worker |
| Pinning | Flat throughput under load | Upgrade Logback/Log4j2; async appenders; `-Djdk.tracePinnedThreads=full` in test runs |
| `ThreadLocal` for heavy objects | Heap bloat, leaks without `.remove()` | Shared `HttpClient` + explicit pass-in; `ScopedValue` for immutable run context (auto GC at scope exit) |
| Token in list, `ofString(token)` on wire | API gets path/id, not file bytes | Worker-level `PayloadStore` + stream (`ofFile` / BodyPublisher) |
| Full payloads in list | OOM / GC fragmentation | Claim Check tokens only; resolve at send in VT |
| No pacing | Spike then silence | Token bucket / target RPS |
| Array scan for metrics | Slow live dashboard | `LongAdder` + ring buffer |
| JVM DNS cache | Traffic pins to one backend IP | `-Dnetworkaddress.cache.ttl=0` on load-test JVM |
| New TCP per request | Ephemeral port exhaustion, `TIME_WAIT` | Shared `HttpClient`, HTTP/2 multiplexing |
| No JIT warm-up | First N requests skew latency/RPS | 10k throwaway warm-up before measured run |

Full detail: [docs/enterprise-scale.md](docs/enterprise-scale.md).

---

## Documentation

| Doc | Topic |
|-----|--------|
| [docs/mvc-structure.md](docs/mvc-structure.md) | Controller / service / model layout |
| [docs/design-patterns.md](docs/design-patterns.md) | Fan-out/fan-in, Strategy, Claim Check, Circuit Breaker |
| [docs/response-validation.md](docs/response-validation.md) | Response size limits, suspicious content |
| [docs/failure-policies.md](docs/failure-policies.md) | Fail-fast, circuit breaker, when to stop |
| [docs/enterprise-scale.md](docs/enterprise-scale.md) | Loom idioms, pacing, metrics, scale traps |

---

## Performance Characteristics (target state)

- **High I/O concurrency** — stateless virtual threads + shared immutable context on Loom-friendly HTTP (no pinning)
- **Lock-free result fan-in** — `AtomicReferenceArray` by task index
- **Bounded memory** — Claim Check tokens in list; response body caps ([response-validation.md](docs/response-validation.md))
- **No task queue** — `List` + one virtual thread per item; backpressure via semaphore or pacing
- **Deterministic load** — optional paced submission (target RPS)
- **Observable runs** — striped counters and latency buffers for live telemetry
- **Representative benchmarks** — JIT warm-up phase before measured run
- **Distributed targets** — DNS cache tuning so traffic spreads across backend IPs
- **Sustainable connection model** — shared HTTP/2 client; OS port exhaustion documented

---

## Build & test

```bash
bash mvnw test
bash mvnw spring-boot:run
```

Pinning check (during load tests):

```bash
java -Djdk.tracePinnedThreads=full -jar target/scale-testing-platform-*.jar
```

Load-test JVM (DNS + optional flags):

```bash
java \
  -Djdk.tracePinnedThreads=full \
  -Dnetworkaddress.cache.ttl=0 \
  -Dnetworkaddress.cache.negative.ttl=0 \
  -jar target/scale-testing-platform-*.jar
```

See [docs/enterprise-scale.md](docs/enterprise-scale.md) for warm-up cycles, HTTP/2 connection reuse, and OS ephemeral port tuning.
