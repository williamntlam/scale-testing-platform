package io.github.williamntlam.scale_testing_platform.model;

import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;

public record TestResponse(
		int taskId,
		TestStatus status,
		String responseBody
) {
	public TestResponse {
		if (taskId < 0) {
			throw new IllegalArgumentException("taskId must be non-negative");
		}
		if (status == null) {
			throw new IllegalArgumentException("status must not be null");
		}
	}
}
