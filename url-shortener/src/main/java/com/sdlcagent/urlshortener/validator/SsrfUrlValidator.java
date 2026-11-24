package com.sdlcagent.urlshortener.validator;

import com.sdlcagent.urlshortener.service.UnsafeUrlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Rejects URLs that point to private or internal destinations (SSRF protection).
 */
@Component
public class SsrfUrlValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfUrlValidator.class);

    /** Resolves a host name to all of its addresses. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    @Autowired
    public SsrfUrlValidator() {
        this(InetAddress::getAllByName);
    }

    public SsrfUrlValidator(HostResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Validates the URL and returns the validated addresses of its host.
     *
     * @throws UnsafeUrlException if the URL is not allowed
     */
    public List<InetAddress> validate(String url) {
        if (url == null || url.isBlank()) {
            throw reject("unknown", "URL is empty");
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw reject("unknown", "URL is malformed");
        }
        return validate(uri);
    }

    public List<InetAddress> validate(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw reject("unknown", "URL scheme must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw reject("unknown", "URL has no host");
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            throw reject(host, "URL host is localhost");
        }

        final InetAddress[] resolved;
        try {
            resolved = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw reject(host, "URL host cannot be resolved");
        }
        if (resolved == null || resolved.length == 0) {
            throw reject(host, "URL host cannot be resolved");
        }
        for (InetAddress address : resolved) {
            String reason = forbiddenReason(address);
            if (reason != null) {
                throw reject(host, "URL host resolves to a " + reason + " address");
            }
        }
        return List.copyOf(Arrays.asList(resolved));
    }

    private static String forbiddenReason(InetAddress a) {
        if (a.isAnyLocalAddress()) {
            return "unspecified";
        }
        if (a.isLoopbackAddress()) {
            return "loopback";
        }
        if (a.isLinkLocalAddress()) {
            return "link-local";
        }
        if (a.isSiteLocalAddress()) {
            return "private";
        }
        if (a.isMulticastAddress()) {
            return "multicast";
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {
                return "unique-local";
            }
        } else if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0) {
                return "unspecified";
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return "private";
            }
        }
        return null;
    }

    private static UnsafeUrlException reject(String host, String reason) {
        log.warn("Rejected unsafe URL, host={}, reason={}", sanitize(host), reason);
        return new UnsafeUrlException(reason);
    }

    private static String sanitize(String host) {
        if (host == null) {
            return "unknown";
        }
        return host.replaceAll("[\\r\\n\\t]", "_");
    }
}
