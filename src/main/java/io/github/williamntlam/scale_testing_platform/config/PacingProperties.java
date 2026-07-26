package io.github.williamntlam.scale_testing_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Default request-start rate applied when a run does not specify one. {@code 0} disables pacing.
 */
@ConfigurationProperties(prefix = "scale-testing.pacing")
public record PacingProperties(int targetRps) {

  public PacingProperties {
    if (targetRps < 0) {
      throw new IllegalArgumentException("targetRps must be non-negative");
    }
  }
}
