package io.github.williamntlam.scale_testing_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scale-testing.failure-policy")
public record FailurePolicyProperties(
    int consecutiveFailureLimit,
    int absoluteFailureLimit,
    int windowSize,
    double failureRateThreshold) {

  public FailurePolicyProperties {
    if (consecutiveFailureLimit < 0) {
      throw new IllegalArgumentException("consecutiveFailureLimit must be non-negative");
    }
    if (absoluteFailureLimit < 0) {
      throw new IllegalArgumentException("absoluteFailureLimit must be non-negative");
    }
    if (windowSize < 0) {
      throw new IllegalArgumentException("windowSize must be non-negative");
    }
    if (failureRateThreshold < 0.0 || failureRateThreshold > 1.0) {
      throw new IllegalArgumentException("failureRateThreshold must be between 0 and 1");
    }
  }
}
