package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import io.github.williamntlam.scale_testing_platform.services.port.ResponseValidator;
import io.github.williamntlam.scale_testing_platform.services.port.ValidatedResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class DefaultResponseValidator implements ResponseValidator {

  private static final int MAX_RESPONSE_BYTES = 65_536;

  @Override
  public ValidatedResponse validate(int httpStatus, byte[] body) {
    byte[] safeBody = body == null ? new byte[0] : body;
    int originalByteCount = safeBody.length;

    if (httpStatus < 200 || httpStatus >= 300) {
      return new ValidatedResponse(TestStatus.FAILED, "HTTP " + httpStatus, originalByteCount);
    }

    if (originalByteCount > MAX_RESPONSE_BYTES) {
      return new ValidatedResponse(
          TestStatus.FAILED, "[truncated: " + originalByteCount + " bytes]", originalByteCount);
    }

    String text = new String(safeBody, StandardCharsets.UTF_8);
    return new ValidatedResponse(TestStatus.SUCCESS, text, originalByteCount);
  }
}
