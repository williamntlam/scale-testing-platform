package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.model.LoadTestRequest;
import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;
import io.github.williamntlam.scale_testing_platform.model.TestResponse;
import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.springframework.stereotype.Service;

@Service
public class LoadTestService {

  private static final int MAX_RESPONSE_BYTES = 65_536;
  private final RequestExecutor requestExecutor;

  public LoadTestService(RequestExecutor requestExecutor) {
    this.requestExecutor = requestExecutor;
  }

  private LoadTestResponse aggregate(AtomicReferenceArray<TestResponse> results) {
    int total = results.length();
    TestResponse[] responses = new TestResponse[total];

    int successCount = 0;
    int failureCount = 0;

    for (int index = 0; index <= total - 1; index++) {
      TestResponse response = results.get(index);
      responses[index] = response;

      if (response.status() == TestStatus.SUCCESS) {
        successCount += 1;
      } else {
        failureCount += 1;
      }
    }

    return new LoadTestResponse(responses, successCount, failureCount);
  }

  private TestResponse executeTask(int taskId, URI targetUri, String payload) throws Exception {
    OutboundResponse response = requestExecutor.send(targetUri, payload);

    int statusCode = response.statusCode();
    byte[] bodyBytes = response.body();

    if (statusCode < 200 || statusCode >= 300) {
      return new TestResponse(taskId, TestStatus.FAILED, "HTTP " + statusCode);
    }

    if (bodyBytes.length > MAX_RESPONSE_BYTES) {
      return new TestResponse(
          taskId, TestStatus.FAILED, "[truncated: " + bodyBytes.length + " bytes]");
    }

    String body = new String(bodyBytes, StandardCharsets.UTF_8);
    return new TestResponse(taskId, TestStatus.SUCCESS, body);
  }

  public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
    List<String> payloads = request.payloads();
    int totalTasks = payloads.size();

    AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(totalTasks);
    CountDownLatch done = new CountDownLatch(totalTasks);
    Semaphore inFlight = new Semaphore(request.concurrencyLimit());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index <= totalTasks - 1; index++) {

        final int taskId = index;
        final String payload = payloads.get(index);

        inFlight.acquire();

        executor.submit(
            () -> {
              try {
                TestResponse result = executeTask(taskId, request.targetUri(), payload);
                results.set(taskId, result);
              } catch (Exception e) {
                results.set(taskId, new TestResponse(taskId, TestStatus.FAILED, e.getMessage()));
              } finally {
                inFlight.release();
                done.countDown();
              }
            });
      }

      done.await();
    }

    return aggregate(results);
  }
}
