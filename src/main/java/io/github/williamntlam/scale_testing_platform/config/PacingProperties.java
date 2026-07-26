package io.github.williamntlam.scale_testing_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scale-testing.pacing")
public record PacingProperties(int targetRps) {

  public PacingProperties {
    if (targetRps < 0) {
      throw new IllegalArgumentException("targetRps must be non-negative");
    }
  }

  /** {@code true} when pacing is disabled (max-out starts). */
  public boolean isUnlimited() {
    return targetRps == 0;
  }
}
