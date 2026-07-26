package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.services.port.PacingStrategy;
import java.util.concurrent.locks.LockSupport;

public final class TokenBucketPacingStrategy implements PacingStrategy {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private final long intervalNanos;
  private long nextAllowedNanos;

  public TokenBucketPacingStrategy(int targetRps) {
    if (targetRps < 1) {
      throw new IllegalArgumentException("targetRps must be >= 1");
    }
    this.intervalNanos = NANOS_PER_SECOND / targetRps;
    this.nextAllowedNanos = System.nanoTime();
  }

  @Override
  public void acquire() {
    long waitUntil = nextAllowedNanos;
    long now = System.nanoTime();

    // parkNanos may return before the deadline, so re-check the clock.
    while (now < waitUntil) {
      LockSupport.parkNanos(waitUntil - now);
      now = System.nanoTime();
    }

    nextAllowedNanos = Math.max(now, waitUntil) + intervalNanos;
  }
}
