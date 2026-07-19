package io.github.williamntlam.scale_testing_platform.model;

import io.github.williamntlam.scale_testing_platform.model.enums.RunAbortPolicy;
import java.net.URI;
import java.util.List;

public record LoadTestRequest(
    List<String> payloads, int concurrencyLimit, URI targetUri, RunAbortPolicy abortPolicy) {

  public LoadTestRequest(List<String> payloads, int concurrencyLimit, URI targetUri) {
    this(payloads, concurrencyLimit, targetUri, RunAbortPolicy.RUN_TO_COMPLETION);
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
  }
}
