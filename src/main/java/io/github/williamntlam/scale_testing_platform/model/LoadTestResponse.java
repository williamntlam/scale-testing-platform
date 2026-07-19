package io.github.williamntlam.scale_testing_platform.model;

import io.github.williamntlam.scale_testing_platform.model.enums.RunOutcome;

public record LoadTestResponse(
    TestResponse[] responses,
    int successCount,
    int failureCount,
    RunOutcome outcome,
    String abortReason) {

  public LoadTestResponse(TestResponse[] responses, int successCount, int failureCount) {
    this(responses, successCount, failureCount, RunOutcome.COMPLETED, null);
  }

  public LoadTestResponse {
    if (responses == null) {
      throw new IllegalArgumentException("responses must not be null");
    }
    if (successCount < 0 || failureCount < 0) {
      throw new IllegalArgumentException("counts must be non-negative");
    }
    if (successCount + failureCount != responses.length) {
      throw new IllegalArgumentException("counts must match responses length");
    }
    if (outcome == null) {
      outcome = RunOutcome.COMPLETED;
    }
  }
}
