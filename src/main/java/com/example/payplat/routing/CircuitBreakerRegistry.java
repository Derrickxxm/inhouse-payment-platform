package com.example.payplat.routing;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerRegistry {
    private final Clock clock;
    private final Map<String, State> states = new HashMap<>();

    private static class State {
        int failures;
        Instant openedAt;
    }

    public CircuitBreakerRegistry(Clock clock) {
        this.clock = clock;
    }

    public synchronized void recordFailure(String id) {
        State state = states.computeIfAbsent(id, key -> new State());
        if (state.failures < 3) state.failures++;
        if (state.failures == 3 && (state.openedAt == null
                || !clock.instant().isBefore(state.openedAt.plusSeconds(30)))) {
            state.openedAt = clock.instant();
        }
    }

    public synchronized void recordSuccess(String id) {
        states.remove(id);
    }

    public synchronized boolean isOpen(String id) {
        State state = states.get(id);
        return state != null && state.openedAt != null
            && clock.instant().isBefore(state.openedAt.plusSeconds(30));
    }
}
