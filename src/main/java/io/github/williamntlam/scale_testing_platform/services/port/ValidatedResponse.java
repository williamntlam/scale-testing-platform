package io.github.williamntlam.scale_testing_platform.services.port;

import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;

public record ValidatedResponse(TestStatus status, String safeBody, int originalByteCount) {}
