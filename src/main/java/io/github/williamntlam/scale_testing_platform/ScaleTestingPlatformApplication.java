package io.github.williamntlam.scale_testing_platform;

import io.github.williamntlam.scale_testing_platform.config.FailurePolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FailurePolicyProperties.class)
public class ScaleTestingPlatformApplication {

  public static void main(String[] args) {
    SpringApplication.run(ScaleTestingPlatformApplication.class, args);
  }
}
