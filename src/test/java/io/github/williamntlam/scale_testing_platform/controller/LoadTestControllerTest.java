package io.github.williamntlam.scale_testing_platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.williamntlam.scale_testing_platform.model.LoadTestResponse;
import io.github.williamntlam.scale_testing_platform.model.TestResponse;
import io.github.williamntlam.scale_testing_platform.model.enums.TestStatus;
import io.github.williamntlam.scale_testing_platform.services.LoadTestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LoadTestController.class)
class LoadTestControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private LoadTestService loadTestService;

  @Test
  void run_returnsLoadTestResponse() throws Exception {
    LoadTestResponse mockResponse =
        new LoadTestResponse(
            new TestResponse[] {new TestResponse(0, TestStatus.SUCCESS, "ok")}, 1, 0);

    when(loadTestService.run(any())).thenReturn(mockResponse);

    mockMvc
        .perform(
            post("/api/load-tests/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "payloads": ["{\\"event\\":\\"ping\\"}"],
                      "concurrencyLimit": 1,
                      "targetUri": "https://httpbin.org/post"
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.successCount").value(1))
        .andExpect(jsonPath("$.failureCount").value(0))
        .andExpect(jsonPath("$.responses[0].status").value("SUCCESS"));
  }
}
