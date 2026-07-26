package io.github.williamntlam.scale_testing_platform.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketPacingStrategyTest {

  @Test
  void acquire_spacesStartsByInterval() {
    TokenBucketPacingStrategy pacing = new TokenBucketPacingStrategy(100);

    long startNanos = System.nanoTime();
    for (int i = 0; i < 10; i++) {
      pacing.acquire();
    }
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

    // 10 starts at 100 RPS means 9 intervals of 10ms; allow slack for scheduling.
    assertTrue(elapsedMillis >= 80, "expected at least 80ms, got " + elapsedMillis);
    assertTrue(elapsedMillis < 1_000, "expected under 1000ms, got " + elapsedMillis);
  }

  @Test
  void acquire_firstCallDoesNotWait() {
    TokenBucketPacingStrategy pacing = new TokenBucketPacingStrategy(1);

    long startNanos = System.nanoTime();
    pacing.acquire();
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

    assertTrue(elapsedMillis < 100, "expected immediate start, got " + elapsedMillis);
  }

  @Test
  void constructor_rejectsNonPositiveRate() {
    assertThrows(IllegalArgumentException.class, () -> new TokenBucketPacingStrategy(0));
    assertThrows(IllegalArgumentException.class, () -> new TokenBucketPacingStrategy(-1));
  }

  @Test
  void noOpPacing_doesNotWait() throws Exception {
    NoOpPacingStrategy pacing = new NoOpPacingStrategy();

    long startNanos = System.nanoTime();
    for (int i = 0; i < 1_000; i++) {
      pacing.acquire();
    }
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

    assertTrue(elapsedMillis < 100, "expected no pacing delay, got " + elapsedMillis);
  }
}
