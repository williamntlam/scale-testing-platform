package io.github.williamntlam.scale_testing_platform.model;

import java.net.URI;
import java.util.List;

public record LoadTestRequest(
    List<String> payloads,
    int concurrencyLimit,
    URI targetUri
) {}