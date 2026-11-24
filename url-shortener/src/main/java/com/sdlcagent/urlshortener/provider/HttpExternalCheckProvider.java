package com.sdlcagent.urlshortener.provider;

import com.sdlcagent.urlshortener.service.ExternalCheckUnavailableException;
import com.sdlcagent.urlshortener.util.ExternalCheckProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP check: connects only to validated addresses (no re-resolution, so no DNS rebinding),
 * never follows redirects, bounded timeout per attempt, bounded fixed-backoff retries.
 */
@Component
@ConditionalOnProperty(name = "external-check.mode", havingValue = "http")
public class HttpExternalCheckProvider implements ExternalCheckProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpExternalCheckProvider.class);
    private static final int MAX_STATUS_LINE = 512;

    private final int timeoutMs;
    private final int maxRetries;
    private final long backoffMs;

    public HttpExternalCheckProvider(ExternalCheckProperties properties) {
        this.timeoutMs = (int) Math.max(1, properties.getTimeoutMs());
        this.maxRetries = Math.max(0, properties.getMaxRetries());
        this.backoffMs = Math.max(0, properties.getBackoffMs());
    }

    @Override
    public void check(URI uri, List<InetAddress> validatedAddresses) {
        String host = uri == null ? null : uri.getHost();
        String scheme = uri == null ? null : uri.getScheme();
        if (host == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || validatedAddresses == null || validatedAddresses.isEmpty()) {
            log.warn("External check rejected unusable target host={}", sanitize(host));
            throw new ExternalCheckUnavailableException("External check unavailable");
        }

        int attempts = 1 + maxRetries;
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                attemptOnce(uri, host, scheme, validatedAddresses);
                return;
            } catch (IOException e) {
                last = e;
                log.warn("External check attempt {}/{} failed host={} reason={}",
                        attempt, attempts, sanitize(host), e.getClass().getSimpleName());
            }
            if (attempt < attempts && backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ExternalCheckUnavailableException("External check unavailable", ie);
                }
            }
        }
        log.warn("External check failed after {} attempts host={}", attempts, sanitize(host));
        throw new ExternalCheckUnavailableException("External check unavailable", last);
    }

    private void attemptOnce(URI uri, String host, String scheme, List<InetAddress> addresses) throws IOException {
        boolean https = scheme.equalsIgnoreCase("https");
        int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);

        IOException lastIo = null;
        for (InetAddress address : addresses) {
            try (Socket plain = new Socket()) {
                plain.connect(new InetSocketAddress(address, port), timeoutMs);
                plain.setSoTimeout(timeoutMs);
                if (https) {
                    SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    try (SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true)) {
                        SSLParameters params = ssl.getSSLParameters();
                        params.setEndpointIdentificationAlgorithm("HTTPS");
                        ssl.setSSLParameters(params);
                        ssl.setSoTimeout(timeoutMs);
                        ssl.startHandshake();
                        exchange(ssl.getInputStream(), ssl.getOutputStream(), uri, host, port, true);
                    }
                } else {
                    exchange(plain.getInputStream(), plain.getOutputStream(), uri, host, port, false);
                }
                return;
            } catch (IOException e) {
                lastIo = e;
            }
        }
        throw lastIo != null ? lastIo : new IOException("no address");
    }

    private void exchange(InputStream in, OutputStream out, URI uri, String host, int port, boolean https)
            throws IOException {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        boolean defaultPort = port == (https ? 443 : 80);
        String hostHeader = host + (defaultPort ? "" : ":" + port);
        String request = "HEAD " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "User-Agent: url-shortener-check\r\n"
                + "Accept: */*\r\n"
                + "Connection: close\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n' && line.size() < MAX_STATUS_LINE) {
            line.write(b);
        }
        String status = line.toString(StandardCharsets.ISO_8859_1).trim();
        String[] parts = status.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("bad status line");
        }
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status code");
        }
        // Redirects are intentionally not followed; any non-5xx answer means the target is reachable.
        if (code >= 500) {
            throw new IOException("server error " + code);
        }
    }

    private static String sanitize(String host) {
        if (host == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < host.length() && i < 255; i++) {
            char c = host.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == ':' || c == '[' || c == ']') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
