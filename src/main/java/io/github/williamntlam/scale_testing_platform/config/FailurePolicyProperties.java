package io.github.williamntlam.scale_testing_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scale-testing.failure-policy")
public record FailurePolicyProperties(int consecutiveFailureLimit, int absoluteFailureLimit) {

  public FailurePolicyProperties {
    if (consecutiveFailureLimit < 0) {
      throw new IllegalArgumentException("consecutiveFailureLimit must be non-negative");
    }
    if (absoluteFailureLimit < 0) {
      throw new IllegalArgumentException("absoluteFailureLimit must be non-negative");
    }
  }
}
