package io.github.williamntlam.scale_testing_platform.services.port;

import java.net.URI;

public interface RequestExecutor {
  OutboundResponse send(URI targetUri, String payload) throws Exception;
}
