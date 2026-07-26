package io.github.williamntlam.scale_testing_platform.services.port;

public interface PacingStrategy {
  void acquire() throws InterruptedException;
}
