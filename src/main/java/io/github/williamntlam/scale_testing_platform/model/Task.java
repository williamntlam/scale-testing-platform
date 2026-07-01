package io.github.williamntlam.scale_testing_platform.model;

public record Task(
		int id,
		String payloadMetadata
) {
	public Task {
		if (id < 0) {
			throw new IllegalArgumentException("id must be non-negative");
		}
		if (payloadMetadata == null) {
			throw new IllegalArgumentException("payloadMetadata must not be null");
		}
	}
}
