package com.sdlcagent.urlshortener.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * Local stub: always succeeds and never touches the network. Default implementation.
 */
@Component
@ConditionalOnProperty(name = "external-check.mode", havingValue = "stub", matchIfMissing = true)
public class StubExternalCheckProvider implements ExternalCheckProvider {

    @Override
    public void check(URI uri, List<InetAddress> validatedAddresses) {
        // intentionally no-op
    }
}
