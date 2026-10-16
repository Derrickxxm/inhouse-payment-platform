package com.example.payplat.routing;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RateLimiterRegistry {
    private final ChannelRegistry registry;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();

    private static class Window {
        final long second;
        int used;

        Window(long second) {
            this.second = second;
        }
    }

    public RateLimiterRegistry(ChannelRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    private Window currentWindow(String id) {
        long second = clock.instant().getEpochSecond();
        Window window = windows.get(id);
        if (window == null || window.second != second) {
            window = new Window(second);
            windows.put(id, window);
        }
        return window;
    }

    private int limit(String id) {
        return registry.byId(id).orElseThrow(() ->
            new IllegalArgumentException("Unknown channel: " + id)).rateLimitPerSec();
    }

    public synchronized boolean tryAcquire(String id) {
        int limit = limit(id);
        Window window = currentWindow(id);
        if (window.used >= limit) return false;
        window.used++;
        return true;
    }

    public synchronized boolean isExhausted(String id) {
        return currentWindow(id).used >= limit(id);
    }
}
