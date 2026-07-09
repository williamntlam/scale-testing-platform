package io.github.williamntlam.scale_testing_platform.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.williamntlam.scale_testing_platform.model.LoadTestRequest;
import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;
import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadTestServiceTest {

  private LoadTestService service;

  @BeforeEach
  void setUp() {
    HttpClient httpClient = HttpClient.newHttpClient();
    service = new LoadTestService(httpClient);
  }

  @Test
  void run_singlePayload_returnsSuccess() throws Exception {
    LoadTestRequest request =
        new LoadTestRequest(
            List.of("{\"event\":\"ping\"}"),
            1,
            URI.create("https://httpbin.org/post"));

    LoadTestResponse response = service.run(request);

    assertEquals(1, response.responses().length);
    assertEquals(1, response.successCount());
    assertEquals(0, response.failureCount());
    assertEquals(TestStatus.SUCCESS, response.responses()[0].status());
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

    for (int i = 0; i < response.responses().length; i++) {
      assertEquals(TestStatus.SUCCESS, response.responses()[i].status());
      assertEquals(i, response.responses()[i].taskId());
    }
  }
}