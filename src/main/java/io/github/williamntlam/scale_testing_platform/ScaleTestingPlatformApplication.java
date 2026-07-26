package io.github.williamntlam.scale_testing_platform;

import io.github.williamntlam.scale_testing_platform.config.FailurePolicyProperties;
import io.github.williamntlam.scale_testing_platform.config.PacingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({FailurePolicyProperties.class, PacingProperties.class})
public class ScaleTestingPlatformApplication {

  public static void main(String[] args) {
    SpringApplication.run(ScaleTestingPlatformApplication.class, args);
  }
}
