package io.github.williamntlam.scale_testing_platform.model;

import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;

public record TestResponse(
		int taskId,
		TestStatus status,
		String responseBody
) {}
