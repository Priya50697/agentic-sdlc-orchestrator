package com.sdlcagent.urlshortener.util;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the external URL check, bound from the external-check.* properties.
 */
@Component
@ConfigurationProperties(prefix = "external-check")
public class ExternalCheckProperties {

    private long timeoutMs = 3000;
    private int maxRetries = 2;
    private long backoffMs = 200;
    private String mode = "stub";

    public long timeoutMs() {
        return timeoutMs;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public long backoffMs() {
        return backoffMs;
    }

    public String mode() {
        return mode;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBackoffMs() {
        return backoffMs;
    }

    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
