package io.github.williamntlam.scale_testing_platform.model;

import io.github.williamntlam.scale_testing_platform.model.enums.RunAbortPolicy;
import java.net.URI;
import java.util.List;

/**
 * @param targetRps request-start rate for this run; {@code null} falls back to the configured
 *     default, {@code 0} disables pacing.
 */
public record LoadTestRequest(
    List<String> payloads,
    int concurrencyLimit,
    URI targetUri,
    RunAbortPolicy abortPolicy,
    Integer targetRps) {

  public LoadTestRequest(List<String> payloads, int concurrencyLimit, URI targetUri) {
    this(payloads, concurrencyLimit, targetUri, RunAbortPolicy.RUN_TO_COMPLETION, null);
  }

  public LoadTestRequest(
      List<String> payloads, int concurrencyLimit, URI targetUri, RunAbortPolicy abortPolicy) {
    this(payloads, concurrencyLimit, targetUri, abortPolicy, null);
  }

  public LoadTestRequest {
    if (payloads == null || payloads.isEmpty()) {
      throw new IllegalArgumentException("payloads must not be empty");
    }
    if (concurrencyLimit < 1) {
      throw new IllegalArgumentException("concurrencyLimit must be at least 1");
    }
    if (targetUri == null) {
      throw new IllegalArgumentException("targetUri must not be null");
    }
    if (abortPolicy == null) {
      abortPolicy = RunAbortPolicy.RUN_TO_COMPLETION;
    }
    if (targetRps != null && targetRps < 0) {
      throw new IllegalArgumentException("targetRps must be non-negative");
    }
  }
}
