package com.sdlcagent.urlshortener.service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thread-safe in-memory fixed-window per-key (client IP) rate limiter.
 * Defaults: 60 requests per 60 seconds.
 */
@Service
public class RateLimitService {

    private record Window(long startMillis, int count) {
    }

    private final Clock clock;
    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepMillis;

    public RateLimitService(Clock clock,
                            @Value("${ratelimit.max-requests:60}") int maxRequests,
                            @Value("${ratelimit.window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxRequests = maxRequests;
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
        this.lastSweepMillis = new AtomicLong(clock.millis());
    }

    /**
     * Registers a request for the given key; throws if the quota is exceeded.
     */
    public void check(String key) {
        long now = clock.millis();
        evictStale(now);
        String k = key == null ? "unknown" : key;
        Window w = windows.compute(k, (id, current) -> {
            if (current == null || now - current.startMillis() >= windowMillis || now < current.startMillis()) {
                return new Window(now, 1);
            }
            int next = current.count() > maxRequests ? current.count() : current.count() + 1;
            return new Window(current.startMillis(), next);
        });
        if (w.count() > maxRequests) {
            long remainingMillis = Math.max(0L, w.startMillis() + windowMillis - now);
            long retryAfter = Math.max(1L, (remainingMillis + 999L) / 1000L);
            throw new RateLimitExceededException(retryAfter);
        }
    }

    /** Clears all state (for tests). */
    public void reset() {
        windows.clear();
        lastSweepMillis.set(clock.millis());
    }

    private void evictStale(long now) {
        long last = lastSweepMillis.get();
        if (now - last >= windowMillis && lastSweepMillis.compareAndSet(last, now)) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMillis);
        }
    }
}
