package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.model.enums.RunAbortPolicy;
import java.util.concurrent.atomic.AtomicInteger;

public class FailureMonitor {

    private final RunAbortPolicy policy;
    private final int consecutiveFailureLimit;
    private final int absoluteFailureLimit;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger totalFailures = new AtomicInteger(0);

    private volatile boolean aborted = false;
    private volatile String abortReason;

    public FailureMonitor(
        RunAbortPolicy policy,
        int consecutiveFailureLimit,
        int absoluteFailureLimit
    ) {
        this.policy = policy == null ? RunAbortPolicy.RUN_TO_COMPLETION : policy;
        this.consecutiveFailureLimit = consecutiveFailureLimit;
        this.absoluteFailureLimit = absoluteFailureLimit;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    public void recordFailure() {
        if (policy != RunAbortPolicy.FAIL_FAST) {
            return;
        }

        int total = totalFailures.incrementAndGet();
        int streak = consecutiveFailures.incrementAndGet();

        if (consecutiveFailureLimit > 0 && streak >= consecutiveFailureLimit) {
            aborted = true;
            abortReason = "consecutive failures reached limit of " + consecutiveFailureLimit;
            return;
        }

        if (absoluteFailureLimit > 0 && total >= absoluteFailureLimit) {
            aborted = true;
            abortReason = "absolute failures reached limit of " + absoluteFailureLimit;
        }

    }

    public boolean shouldAbort() {
        return aborted;
    }

    public String abortReason() {
        return abortReason;
    }

}