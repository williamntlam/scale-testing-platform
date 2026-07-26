package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.config.FailurePolicyProperties;
import io.github.williamntlam.scale_testing_platform.config.PacingProperties;
import io.github.williamntlam.scale_testing_platform.model.LoadTestRequest;
import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;
import io.github.williamntlam.scale_testing_platform.model.TestResponse;
import io.github.williamntlam.scale_testing_platform.model.enums.RunOutcome;
import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import io.github.williamntlam.scale_testing_platform.services.port.OutboundResponse;
import io.github.williamntlam.scale_testing_platform.services.port.PacingStrategy;
import io.github.williamntlam.scale_testing_platform.services.port.RequestExecutor;
import io.github.williamntlam.scale_testing_platform.services.port.ResponseValidator;
import io.github.williamntlam.scale_testing_platform.services.port.ValidatedResponse;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.springframework.stereotype.Service;

@Service
public class LoadTestService {

  private final RequestExecutor requestExecutor;
  private final ResponseValidator responseValidator;
  private final FailurePolicyProperties failurePolicyProperties;
  private final PacingProperties pacingProperties;

  public LoadTestService(
      RequestExecutor requestExecutor,
      ResponseValidator responseValidator,
      FailurePolicyProperties failurePolicyProperties,
      PacingProperties pacingProperties) {
    this.requestExecutor = requestExecutor;
    this.responseValidator = responseValidator;
    this.failurePolicyProperties = failurePolicyProperties;
    this.pacingProperties = pacingProperties;
  }

  private static PacingStrategy pacingFor(PacingProperties properties) {
    if (properties.isUnlimited()) {
      return new NoOpPacingStrategy();
    }
    return new TokenBucketPacingStrategy(properties.targetRps());
  }

  private LoadTestResponse aggregate(
      AtomicReferenceArray<TestResponse> results, FailureMonitor monitor) {
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

    RunOutcome outcome = monitor.shouldAbort() ? RunOutcome.ABORTED : RunOutcome.COMPLETED;

    return new LoadTestResponse(
        responses, successCount, failureCount, outcome, monitor.abortReason());
  }

  private TestResponse executeTask(int taskId, URI targetUri, String payload) throws Exception {
    OutboundResponse response = requestExecutor.send(targetUri, payload);

    ValidatedResponse validated =
        responseValidator.validate(response.statusCode(), response.body());

    return new TestResponse(taskId, validated.status(), validated.safeBody());
  }

  public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
    List<String> payloads = request.payloads();
    int totalTasks = payloads.size();

    AtomicReferenceArray<TestResponse> results = new AtomicReferenceArray<>(totalTasks);
    CountDownLatch done = new CountDownLatch(totalTasks);
    Semaphore inFlight = new Semaphore(request.concurrencyLimit());
    PacingStrategy pacing = pacingFor(pacingProperties);
    FailureMonitor monitor =
        new FailureMonitor(
            request.abortPolicy(),
            failurePolicyProperties.consecutiveFailureLimit(),
            failurePolicyProperties.absoluteFailureLimit());

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index <= totalTasks - 1; index++) {

        if (monitor.shouldAbort()) {
          String reason = monitor.abortReason();
          for (int skipped = index; skipped < totalTasks; skipped++) {
            results.set(
                skipped, new TestResponse(skipped, TestStatus.FAILED, "skipped: " + reason));
            done.countDown();
          }
          break;
        }

        final int taskId = index;
        final String payload = payloads.get(index);

        pacing.acquire();
        inFlight.acquire();

        executor.submit(
            () -> {
              try {
                TestResponse result = executeTask(taskId, request.targetUri(), payload);
                results.set(taskId, result);
                if (result.status() == TestStatus.SUCCESS) {
                  monitor.recordSuccess();
                } else {
                  monitor.recordFailure();
                }
              } catch (Exception e) {
                results.set(taskId, new TestResponse(taskId, TestStatus.FAILED, e.getMessage()));
                monitor.recordFailure();
              } finally {
                inFlight.release();
                done.countDown();
              }
            });
      }

      done.await();
    }

    return aggregate(results, monitor);
  }
}
