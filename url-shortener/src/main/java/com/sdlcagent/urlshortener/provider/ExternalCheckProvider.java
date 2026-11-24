package com.sdlcagent.urlshortener.provider;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * Outbound URL check. Implementations must only contact the supplied, already validated addresses.
 */
public interface ExternalCheckProvider {

    /**
     * Performs the external check.
     *
     * @param uri                the URL being checked
     * @param validatedAddresses addresses that passed SSRF validation for the URL host
     * @throws com.sdlcagent.urlshortener.service.ExternalCheckUnavailableException when the check fails after retries
     */
    void check(URI uri, List<InetAddress> validatedAddresses);
}
