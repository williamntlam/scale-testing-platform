package io.github.williamntlam.scale_testing_platform.model;

import java.net.URI;
import java.util.List;

public record LoadTestRequest(List<String> payloads, int concurrencyLimit, URI targetUri) {
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
  }
}
