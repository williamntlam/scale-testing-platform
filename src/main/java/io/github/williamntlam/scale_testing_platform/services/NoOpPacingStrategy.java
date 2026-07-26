package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.services.port.PacingStrategy;

public final class NoOpPacingStrategy implements PacingStrategy {

  @Override
  public void acquire() {
    // unlimited starts
  }
}
