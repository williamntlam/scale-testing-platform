package io.github.williamntlam.scale_testing_platform.model;

public record LoadTestResponse(
		TestResponse[] responses,
		int successCount,
		int failureCount
) {}
