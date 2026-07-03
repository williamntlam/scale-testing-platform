package io.github.williamntlam.scale_testing_platform.services;

import java.net.http.HttpClient;

import org.springframework.stereotype.Service;

import io.github.williamntlam.scale_testing_platform.model.LoadTestRequest;
import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;

@Service
public class LoadTestService {

    private static final int MAX_RESPONSE_BYTES = 65_536;
    private final HttpClient httpClient;

    public LoadTestService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public LoadTestResponse run(LoadTestRequest request) throws InterruptedException {
        throw new UnsupportedOperationException("Not yet implemented");
    }

}
