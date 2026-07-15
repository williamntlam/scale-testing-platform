package io.github.williamntlam.scale_testing_platform.services.port;

public interface ResponseValidator {
  ValidatedResponse validate(int httpStatus, byte[] body);
}
