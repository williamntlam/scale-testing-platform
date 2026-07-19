package io.github.williamntlam.scale_testing_platform.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.williamntlam.scale_testing_platform.model.enums.RunAbortPolicy;
import org.junit.jupiter.api.Test;

class FailureMonitorTest {

  @Test
  void failFast_abortsAfterConsecutiveLimit() {
    FailureMonitor monitor = new FailureMonitor(RunAbortPolicy.FAIL_FAST, 5, 0);

    for (int i = 0; i < 4; i++) {
      monitor.recordFailure();
      assertFalse(monitor.shouldAbort());
    }

    monitor.recordFailure();
    assertTrue(monitor.shouldAbort());
    assertTrue(monitor.abortReason().contains("consecutive"));
  }

  @Test
  void success_resetsConsecutiveStreak() {
    FailureMonitor monitor = new FailureMonitor(RunAbortPolicy.FAIL_FAST, 3, 0);

    monitor.recordFailure();
    monitor.recordFailure();
    monitor.recordSuccess();
    monitor.recordFailure();

    assertFalse(monitor.shouldAbort());
  }

  @Test
  void failFast_abortsOnAbsoluteLimit() {
    FailureMonitor monitor = new FailureMonitor(RunAbortPolicy.FAIL_FAST, 0, 3);

    monitor.recordFailure();
    monitor.recordSuccess();
    monitor.recordFailure();
    assertFalse(monitor.shouldAbort());

    monitor.recordFailure();
    assertTrue(monitor.shouldAbort());
    assertTrue(monitor.abortReason().contains("absolute"));
  }

  @Test
  void runToCompletion_neverAborts() {
    FailureMonitor monitor = new FailureMonitor(RunAbortPolicy.RUN_TO_COMPLETION, 1, 1);

    for (int i = 0; i < 20; i++) {
      monitor.recordFailure();
    }

    assertFalse(monitor.shouldAbort());
    assertEquals(null, monitor.abortReason());
  }
}
