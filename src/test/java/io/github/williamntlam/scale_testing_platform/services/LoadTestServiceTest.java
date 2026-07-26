package io.github.williamntlam.scale_testing_platform.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.williamntlam.scale_testing_platform.config.FailurePolicyProperties;
import io.github.williamntlam.scale_testing_platform.config.PacingProperties;
import io.github.williamntlam.scale_testing_platform.model.LoadTestRequest;
import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;
import io.github.williamntlam.scale_testing_platform.model.enums.RunAbortPolicy;
import io.github.williamntlam.scale_testing_platform.model.enums.RunOutcome;
import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import io.github.williamntlam.scale_testing_platform.services.port.OutboundResponse;
import io.github.williamntlam.scale_testing_platform.services.port.RequestExecutor;
import io.github.williamntlam.scale_testing_platform.services.port.ResponseValidator;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadTestServiceTest {

  private static final FailurePolicyProperties FAILURE_POLICY = new FailurePolicyProperties(5, 0);
  private static final PacingProperties PACING = new PacingProperties(0); // unlimited

  private LoadTestService service;

  @BeforeEach
  void setUp() {
    HttpClient httpClient = HttpClient.newHttpClient();
    RequestExecutor requestExecutor = new HttpRequestExecutor(httpClient);
    ResponseValidator responseValidator = new DefaultResponseValidator();
    service = new LoadTestService(requestExecutor, responseValidator, FAILURE_POLICY, PACING);
  }

  @Test
  void run_singlePayload_returnsSuccess() throws Exception {
    LoadTestRequest request =
        new LoadTestRequest(
            List.of("{\"event\":\"ping\"}"), 1, URI.create("https://httpbin.org/post"));

    LoadTestResponse response = service.run(request);

    assertEquals(1, response.responses().length);
    assertEquals(1, response.successCount());
    assertEquals(0, response.failureCount());
    assertEquals(TestStatus.SUCCESS, response.responses()[0].status());
    assertEquals(RunOutcome.COMPLETED, response.outcome());
    assertNotNull(response.responses()[0].responseBody());
  }

  @Test
  void run_multiplePayloads_allSucceed() throws Exception {
    LoadTestRequest request =
        new LoadTestRequest(
            List.of("{\"event\":\"one\"}", "{\"event\":\"two\"}"),
            2,
            URI.create("https://httpbin.org/post"));

    LoadTestResponse response = service.run(request);

    assertEquals(2, response.responses().length);
    assertEquals(2, response.successCount());
    assertEquals(0, response.failureCount());
    assertEquals(RunOutcome.COMPLETED, response.outcome());

    for (int i = 0; i < response.responses().length; i++) {
      assertEquals(TestStatus.SUCCESS, response.responses()[i].status());
      assertEquals(i, response.responses()[i].taskId());
    }
  }

  @Test
  void run_http500_returnsFailure() throws Exception {
    LoadTestRequest request =
        new LoadTestRequest(
            List.of("{\"event\":\"ping\"}"), 1, URI.create("https://httpbin.org/status/500"));

    LoadTestResponse response = service.run(request);

    assertEquals(1, response.failureCount());
    assertEquals(0, response.successCount());
    assertEquals(TestStatus.FAILED, response.responses()[0].status());
    assertEquals(RunOutcome.COMPLETED, response.outcome());
  }

  @Test
  void run_failFast_skipsRemainingAfterConsecutiveFailures() throws Exception {
    RequestExecutor alwaysFailing = (targetUri, payload) -> new OutboundResponse(500, new byte[0]);
    LoadTestService failFastService =
        new LoadTestService(alwaysFailing, new DefaultResponseValidator(), FAILURE_POLICY, PACING);

    LoadTestRequest request =
        new LoadTestRequest(
            List.of("a", "b", "c", "d", "e", "f", "g", "h"),
            1,
            URI.create("https://example.com"),
            RunAbortPolicy.FAIL_FAST);

    LoadTestResponse response = failFastService.run(request);

    assertEquals(RunOutcome.ABORTED, response.outcome());
    assertNotNull(response.abortReason());
    assertTrue(response.abortReason().contains("consecutive"));
    assertEquals(8, response.responses().length);
    assertEquals(8, response.failureCount());

    long skipped =
        java.util.Arrays.stream(response.responses())
            .filter(r -> r.responseBody() != null && r.responseBody().startsWith("skipped:"))
            .count();
    assertTrue(skipped > 0);
  }

  @Test
  void run_runToCompletion_processesAllFailures() throws Exception {
    RequestExecutor alwaysFailing =
        (targetUri, payload) -> new OutboundResponse(500, "err".getBytes(StandardCharsets.UTF_8));
    LoadTestService completionService =
        new LoadTestService(alwaysFailing, new DefaultResponseValidator(), FAILURE_POLICY, PACING);

    LoadTestRequest request =
        new LoadTestRequest(
            List.of("a", "b", "c", "d", "e", "f"),
            2,
            URI.create("https://example.com"),
            RunAbortPolicy.RUN_TO_COMPLETION);

    LoadTestResponse response = completionService.run(request);

    assertEquals(RunOutcome.COMPLETED, response.outcome());
    assertEquals(6, response.failureCount());
    assertEquals(0, response.successCount());
  }
}
