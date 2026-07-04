package io.github.williamntlam.scale_testing_platform.services;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.springframework.stereotype.Service;

import io.github.williamntlam.scale_testing_platform.model.LoadTestRequest;
import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;
import io.github.williamntlam.scale_testing_platform.model.TestResponse;
import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;

@Service
public class LoadTestService {

    private static final int MAX_RESPONSE_BYTES = 65_536;
    private final HttpClient httpClient;

    public LoadTestService(HttpClient httpClient) {
        this.httpClient = httpClient;
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

                executor.submit(() -> {
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
