package io.github.williamntlam.scale_testing_platform.services;

import io.github.williamntlam.scale_testing_platform.services.port.OutboundResponse;
import io.github.williamntlam.scale_testing_platform.services.port.RequestExecutor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class HttpRequestExecutor implements RequestExecutor {

  private final HttpClient httpClient;

  public HttpRequestExecutor(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public OutboundResponse send(URI targetUri, String payload) throws Exception {
    HttpRequest httpRequest =
        HttpRequest.newBuilder()
            .uri(targetUri)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(30))
            .build();

    HttpResponse<byte[]> response =
        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

    return new OutboundResponse(response.statusCode(), response.body());
  }
}
