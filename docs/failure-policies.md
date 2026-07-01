# Failure Policies & Early Abort

When many requests fail in a row — or failure rate spikes — should the load test **stop**? **Keep going**? **Pause**?

There is no single right answer. It depends on **why** you're running the test. This doc explains the options so you can pick a policy and implement it in `service/` during Step 2 or 3.

---

## Why this matters

Without a policy, a load tester will happily hammer a **dead or misconfigured target** until every task finishes:

```text
10,000 tasks × target is down = 10,000 failures, wasted time, noisy logs
```

Worse: you may **hurt the target** (retry storms) or **skew results** (measuring timeouts, not real throughput).

A **failure policy** defines when to change behavior based on live failure signals.

---

## Is stopping the right thing?

| Test goal | Usually best policy |
|-----------|---------------------|
| **Smoke test** (“is it up?”) | **Stop early** on first burst of failures |
| **Capacity / stress test** (“how does it behave under load?”) | **Continue** — failures *are* the data |
| **Soak test** (hours of steady load) | **Circuit breaker** — pause or stop if target is clearly unhealthy |
| **Regression benchmark** (compare runs over time) | **Continue** full run, flag run as `DEGRADED` in response |
| **Testing against production** | **Stop or circuit-break early** — protect the target |

**Stopping is right** when continuing gives no useful signal or causes harm.

**Continuing is right** when you need the full failure curve, latency under errors, or recovery behavior.

---

## Policy options

Define an enum (e.g. in `model/enums/RunAbortPolicy.java` or config-only at first):

| Policy | Behavior |
|--------|----------|
| `RUN_TO_COMPLETION` | Default — process all tasks regardless of failures |
| `FAIL_FAST` | Abort as soon as failure threshold is hit |
| `CIRCUIT_BREAKER` | After threshold, **stop sending new work**; drain or cancel in-flight tasks |
| `PAUSE_AND_RETRY` | Back off, retry later (advanced; rarely needed in v1) |

Expose the chosen policy on `LoadTestRequest` (optional field) or `application.yaml` default.

---

## Signals to watch

Track these **in the service layer** as workers complete tasks (thread-safe counters):

| Signal | Example threshold | Typical use |
|--------|-------------------|-------------|
| **Consecutive failures** | 10 in a row | Smoke test — something is fundamentally broken |
| **Failure rate (sliding window)** | > 50% over last 100 requests | Circuit breaker — target is melting down |
| **Absolute failure count** | 500 failures | Cap damage during long runs |
| **Timeout rate** | > 30% timeouts | Network or target overload |
| **HTTP 5xx rate** | > 20% | Server-side distress |

Use `AtomicInteger` / `LongAdder` for counts; a small circular buffer or atomic window for rate.

**Do not** stop on a single failure — always use a threshold to avoid flapping on transient errors.

---

## Circuit breaker (recommended pattern)

Classic **Circuit Breaker** has three states:

```text
CLOSED  →  normal; requests flow
OPEN    →  failures exceeded threshold; reject new work immediately
HALF_OPEN  →  (optional) allow a probe request to see if target recovered
```

For your load tester, a simplified version is enough at first:

```text
CLOSED  →  workers send requests
OPEN    →  workers stop dequeuing new tasks OR skip HTTP and mark FAILED instantly
```

**Why not full Hystrix-style half-open?** You're not protecting a microservice caller — you're driving a test. Opening the circuit means: *“target looks dead; stop wasting resources.”*

### Where it fits

```text
Worker polls task from queue
        ↓
CircuitBreaker.allowRequest()?  ──no──→  mark FAILED ("circuit open"), skip HTTP
        ↓ yes
Send HTTP → validate response → TestResponse
        ↓
Record success/failure → maybe trip breaker
```

Implement as `service/CircuitBreaker.java` or `service/FailureMonitor.java`.

---

## Fail fast vs drain

When abort triggers, two sub-options:

| Mode | What happens |
|------|----------------|
| **Hard stop** | `CountDownLatch` / interrupt workers; return partial `LoadTestResponse` |
| **Drain queue** | Stop **feeding** new tasks; let in-flight requests finish |
| **Cancel in-flight** | Aggressive; use only if you must stop immediately |

**Recommendation:** **Stop feeding the queue + drain in-flight** — cleaner metrics, fewer orphaned HTTP calls.

```text
Main thread: stop putting tasks on queue (or set abort flag)
Workers: finish current task, see abort flag, exit loop
Fan-in: await latch, return results collected so far
```

---

## What to return when aborted early

Extend `LoadTestResponse` later (optional fields):

```java
public record LoadTestResponse(
    TestResponse[] responses,
    int successCount,
    int failureCount,
    RunOutcome outcome,       // COMPLETED | ABORTED | DEGRADED
    String abortReason        // e.g. "failure rate exceeded 50% in window of 100"
) {}
```

```java
public enum RunOutcome {
    COMPLETED,   // all tasks processed under policy
    ABORTED,     // stopped early by policy
    DEGRADED     // finished but failure rate above warning threshold
}
```

For Step 2, `COMPLETED` only is fine — add `ABORTED` when you implement policies.

---

## Suggested defaults (sensible starting point)

```yaml
scale-testing:
  failure-policy:
    mode: RUN_TO_COMPLETION        # safe default for stress tests
    # Optional overrides per request or profile:
    consecutive-failure-limit: 0   # 0 = disabled
    failure-rate-threshold: 0.0    # 0 = disabled (e.g. 0.5 = 50%)
    failure-rate-window-size: 100  # last N tasks for rate calculation
    min-samples-before-abort: 20   # don't abort until at least N tasks completed
```

**Smoke-test profile example:**

```yaml
# application-smoke.yaml
scale-testing:
  failure-policy:
    mode: FAIL_FAST
    consecutive-failure-limit: 5
    min-samples-before-abort: 5
```

**Stress-test profile (your README use case):**

```yaml
scale-testing:
  failure-policy:
    mode: RUN_TO_COMPLETION
    failure-rate-threshold: 0.0    # never auto-abort; report everything
```

---

## Implementation sketch

### `FailureMonitor` (service/)

```java
public class FailureMonitor {
    void recordSuccess();
    void recordFailure();
    boolean shouldAbort();  // checks policy + thresholds
    String abortReason();
}
```

Workers call `recordSuccess()` / `recordFailure()` after each task.

Before sending HTTP (or before dequeuing), check `shouldAbort()`.

### Thread safety

All counters must be atomic — multiple virtual threads update concurrently.

### Interaction with Producer–Consumer

- **Producer loop:** check `shouldAbort()` before `taskBuffer.put(...)`
- **Consumer loop:** check before `poll()` or after acquiring a task
- Set a volatile `boolean aborted` so all workers see the flag

---

## Stress test vs failure policy (terminology)

| Term | Meaning here |
|------|----------------|
| **Load / stress test** | The whole feature — many concurrent requests |
| **Failure policy** | Rules for stopping or continuing when errors pile up |
| **Circuit breaker** | One implementation of a failure policy |

You are building a **stress tester** that optionally **aborts** when failure signals say the run is no longer useful or is unsafe.

---

## Testing checklist

Unit-test `FailureMonitor` without HTTP:

- [ ] All successes → never abort
- [ ] 5 consecutive failures with limit 5 → abort
- [ ] 40% failure rate with threshold 50% → no abort
- [ ] 60% failure rate with threshold 50% after `minSamples` → abort
- [ ] Below `minSamplesBeforeAbort` → never abort (avoid false positives)
- [ ] `RUN_TO_COMPLETION` → never abort regardless of failures

Integration test: mock executor that always fails → verify run ends early with `ABORTED` outcome.

---

## Phased rollout (for you to code)

| Phase | What to build |
|-------|----------------|
| **Step 2** | `RUN_TO_COMPLETION` only; track `successCount` / `failureCount` |
| **Step 3** | `FailureMonitor` + `FAIL_FAST` on consecutive failures |
| **Step 4** | Failure-rate window + `CIRCUIT_BREAKER` + `RunOutcome` on response |
| **Later** | Per-request policy on `LoadTestRequest`, smoke vs stress profiles |

---

## Related docs

- [Design Patterns](./design-patterns.md) — Circuit Breaker, Observer (metrics), Strategy (policy implementations)
- [Response Validation](./response-validation.md) — what counts as a “failure” vs “suspicious”
- [MVC Structure](./mvc-structure.md) — failure logic lives in `service/`, not controller
