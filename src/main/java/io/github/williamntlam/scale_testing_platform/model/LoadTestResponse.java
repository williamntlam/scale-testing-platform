package io.github.williamntlam.scale_testing_platform.model;

public record LoadTestResponse(TestResponse[] responses, int successCount, int failureCount) {
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
  }
}
