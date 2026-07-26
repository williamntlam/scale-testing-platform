# Scale Testing Platform

A lock-free, virtual-threaded benchmarking and traffic-generation engine built in Java 21 / Spring Boot 4. Designed to simulate massive enterprise workloads (high-volume media ingestion, financial transaction processing) with high throughput and minimal lock contention — using **Project Loom idioms**, not legacy thread-pool patterns.

---

## Current status

| Area | Status |
|------|--------|
| List-driven fan-out (one VT per payload) | **Done** — `LoadTestService` |
| Lock-free fan-in (`AtomicReferenceArray` + latch) | **Done** |
| Max in-flight (`Semaphore`) | **Done** — via `concurrencyLimit` |
| Shared HTTP/2 `HttpClient` bean | **Done** — `HttpClientConfig` |
| `RequestExecutor` Strategy + `HttpRequestExecutor` | **Done** |
| REST API `POST /api/load-tests/run` | **Done** — `LoadTestController` |
| Response size cap + non-2xx → `FAILED` | **Done** — inline in `LoadTestService` (64 KB) |
| Pacing / target RPS | **Done** — `PacingStrategy` (`scale-testing.pacing.target-rps` or per-request `targetRps`) |
| Pluggable `ResponseValidator` | **Done** — `DefaultResponseValidator` |
| Failure policies (`FAIL_FAST`, consecutive/absolute limits) | **Done** — `FailureMonitor` + `RunOutcome` |
| Claim Check / `PayloadStore` | Planned |
| Circuit breaker (sliding-window failure rate) | Planned |
| Live metrics (`LongAdder` + ring buffer) | Planned |
| JIT warm-up phase | Planned |

---

## Architectural Philosophy

Traditional scale-testing frameworks rely on OS-level thread pools (`ThreadPoolExecutor`). Under heavy parallel I/O, thousands of platform threads block and the kernel spends CPU on context switches.

**Scale Testing Platform** shifts concurrency to the JVM using **Virtual Threads (Project Loom)** and **lock-free fan-in** (`AtomicReferenceArray` indexed by task id) to avoid contended locks on the result path.

Important nuance at enterprise scale:

- **Gold standard for Loom:** virtual threads should be **entirely stateless** — use **shared, immutable context** (explicit injection for `HttpClient`/executors; `ScopedValue` for lightweight run metadata). No `ThreadLocal`, no per-VT heavy state.
- Virtual threads excel when tasks are **short-lived and I/O-bound** — spawn **one virtual thread per request**, not a fixed pool of workers polling a queue.
- **Carrier pinning** (`synchronized` in libraries) silently destroys throughput — update **Logback/Log4j2** to versions that use `ReentrantLock` instead of `synchronized` where possible; detect pins with `-Djdk.tracePinnedThreads=full` during testing.
- **Heavy dependencies** (`HttpClient`, executors) — instantiate once at the app root; **pass explicitly** into each virtual thread (highest performance). Use `ScopedValue` only for lightweight run metadata on deep stacks.
- **Claim Check** means the **payload list** carries **lightweight tokens**; each virtual thread **resolves and streams** heavy data at send time — not `ofString(token)` on the wire. *(Not yet implemented — payloads are currently inline strings.)*
- **No `BlockingQueue`** — with one virtual thread per request, iterate `List<String>` (or a stream) directly; the data drives the loop ([enterprise-scale.md](docs/enterprise-scale.md)).
- **Steady-state load** requires explicit **pacing** (target RPS), not max-out submission alone. The semaphore limits concurrency; `PacingStrategy` spaces request **starts**.

See [docs/enterprise-scale.md](docs/enterprise-scale.md) for pitfalls, fixes, and remaining rollout phases.

---

## Core Architecture Layers

### 1. User-Space Thread Multiplexing

Virtual threads map onto a small set of carrier threads. When a virtual thread blocks on Loom-friendly I/O (`java.net.http`), it unmounts and frees the carrier.

- Blocking HTTP → virtual thread yields (if not **pinned**)
- Carriers stay busy with other virtual threads
- **Anti-pattern:** long-lived “worker loops” that poll a queue — use **one virtual thread per task** instead

### 2. Lock-Free Fan-In (`AtomicReferenceArray`)

Each task has a unique index. Workers write only to `results[taskId]` — no lock on the result array.

- Pre-allocate `AtomicReferenceArray<TestResponse>` sized to task count
- Suitable for **final per-task reports**
- For **live RPS / P99**, add `LongAdder` and a latency ring buffer (not array scans) — [enterprise-scale.md](docs/enterprise-scale.md)

### 3. Claim Check Pipeline (planned)

Heavy assets stay off the hot execution path. The **list holds tokens only**; each virtual thread **resolves and streams** at send time.

**The paradox:** `BodyPublishers.ofString(token)` sends the path, not the file. Putting full payloads in the list causes OOM — that is not Claim Check. See [enterprise-scale.md](docs/enterprise-scale.md) § Claim Check memory leak paradox.

```text
List / payload ref   →  lightweight token (path, key, id)
Virtual thread       →  PayloadStore resolves → stream (file / ByteBuffer)
HttpClient           →  BodyPublishers.ofFile(path) or streaming BodyPublisher
Target API           →  receives bytes, not the token string
```

Today, `payloads` are small inline strings posted via `BodyPublishers.ofString`.

---

## Tech Stack & Prerequisites

- **Language:** Java 21+ (Virtual Threads / Loom)
- **Framework:** Spring Boot 4.1
- **Build:** Maven (`./mvnw`)
- **HTTP:** `java.net.http.HttpClient` (HTTP/2 preferred)
- **Concurrency:** `java.util.concurrent`, `java.util.concurrent.atomic`
- **App structure:** Spring Boot MVC — see [docs/mvc-structure.md](docs/mvc-structure.md)

---

## How the engine works (implemented)

`LoadTestService.run()` walks the payload list and submits one virtual thread per item. A `Semaphore` caps in-flight work; a `CountDownLatch` waits for all tasks.

```text
List<String> payloads  →  for each index i
                            →  pacing.acquire()
                            →  semaphore.acquire()
                            →  executor.submit(task i)
                              →  results[i] = TestResponse
                              →  semaphore.release()
                              →  done.countDown()
```

Core flow (mirrors current code):

```java
public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
    List<String> payloads = request.payloads();
    int totalTasks = payloads.size();

    AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(totalTasks);
    CountDownLatch done = new CountDownLatch(totalTasks);
    Semaphore inFlight = new Semaphore(request.concurrencyLimit());
    PacingStrategy pacing = pacingFor(request.targetRps());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < totalTasks; i++) {
            final int taskId = i;
            final String payload = payloads.get(i);

            pacing.acquire();
            inFlight.acquire();
            executor.submit(() -> {
                try {
                    results.set(taskId, executeTask(taskId, request.targetUri(), payload));
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

Outbound HTTP goes through `RequestExecutor` (implemented by `HttpRequestExecutor` with the shared `HttpClient` bean). After each call, the service treats non-2xx as `FAILED` and rejects bodies larger than **65,536 bytes**.

### Concurrency & pacing

| Mode | Mechanism | Status |
|------|-----------|--------|
| Max in-flight | `Semaphore(concurrencyLimit)` before submit | **Done** |
| Unlimited (stress) | Set `concurrencyLimit` very high; leave `targetRps` at `0` | **Done** |
| Steady RPS | `pacing.acquire()` before the semaphore | **Done** |

`concurrencyLimit` caps how many requests are **in flight**; `targetRps` caps how often a new request **starts**. With fast responses a small concurrency limit can still produce thousands of starts per second, which is why both knobs exist.

```yaml
scale-testing:
  pacing:
    target-rps: 0  # default when a run omits targetRps; 0 = unlimited
```

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
| [docs/mvc-structure.md](docs/mvc-structure.md) | Controller / services / model layout |
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

## API

```http
POST /api/load-tests/run
Content-Type: application/json

{
  "payloads": ["{\"event\":\"ping\"}"],
  "concurrencyLimit": 10,
  "targetUri": "https://httpbin.org/post",
  "abortPolicy": "RUN_TO_COMPLETION",
  "targetRps": 100
}
```

`abortPolicy` and `targetRps` are optional. Omitting `targetRps` uses the configured default; `0` disables pacing for that run.

```json
{
  "responses": [
    { "taskId": 0, "status": "SUCCESS", "responseBody": "..." }
  ],
  "successCount": 1,
  "failureCount": 0
}
```

---

## Build & test

```bash
./mvnw test
./mvnw spring-boot:run
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
