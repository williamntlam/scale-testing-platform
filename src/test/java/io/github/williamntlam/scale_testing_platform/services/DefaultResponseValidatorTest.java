package io.github.williamntlam.scale_testing_platform.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import io.github.williamntlam.scale_testing_platform.services.port.ResponseValidator;
import io.github.williamntlam.scale_testing_platform.services.port.ValidatedResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DefaultResponseValidatorTest {

  private final ResponseValidator validator = new DefaultResponseValidator();

  @Test
  void success_smallBody() {
    ValidatedResponse result =
        validator.validate(200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

    assertEquals(TestStatus.SUCCESS, result.status());
    assertEquals("{\"ok\":true}", result.safeBody());
  }

  @Test
  void failure_http500() {
    ValidatedResponse result = validator.validate(500, new byte[0]);

    assertEquals(TestStatus.FAILED, result.status());
    assertEquals("HTTP 500", result.safeBody());
  }

  @Test
  void failure_bodyTooLarge() {
    byte[] huge = new byte[65_537];
    ValidatedResponse result = validator.validate(200, huge);

    assertEquals(TestStatus.FAILED, result.status());
    assertTrue(result.safeBody().startsWith("[truncated:"));
  }
}
