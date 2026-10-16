package com.example.payplat.routing;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

@Component
public class Router {
    private final ChannelRegistry registry;
    private final RateLimiterRegistry limiter;
    private final CircuitBreakerRegistry circuits;

    public Router(ChannelRegistry registry, RateLimiterRegistry limiter,
                  CircuitBreakerRegistry circuits) {
        this.registry = registry;
        this.limiter = limiter;
        this.circuits = circuits;
    }

    public List<String> rank(Tier tier) {
        Comparator<ChannelProperties> comparator = switch (tier) {
            case CHEAPEST -> Comparator.comparingInt(ChannelProperties::feeBps);
            case FASTEST -> Comparator.comparingInt(ChannelProperties::latencyMs);
            case MOST_RELIABLE -> Comparator.comparingDouble(
                (ChannelProperties c) -> c.successRate() * c.stability()).reversed();
            case BALANCED -> Comparator.comparingDouble(this::balancedScore).reversed();
        };
        return registry.all().stream()
            .filter(c -> !c.maintenance() && !limiter.isExhausted(c.id()) && !circuits.isOpen(c.id()))
            .sorted(comparator.thenComparing(ChannelProperties::id))
            .map(ChannelProperties::id).toList();
    }

    private double balancedScore(ChannelProperties channel) {
        return normalized(channel, ChannelProperties::feeBps, true)
            + normalized(channel, ChannelProperties::latencyMs, true)
            + normalized(channel, ChannelProperties::stability, false)
            + normalized(channel, ChannelProperties::successRate, false);
    }

    private double normalized(ChannelProperties channel, ToDoubleFunction<ChannelProperties> value,
                              boolean lowerIsBetter) {
        var stats = registry.all().stream().mapToDouble(value).summaryStatistics();
        double range = stats.getMax() - stats.getMin();
        if (range == 0) return 0;
        double x = value.applyAsDouble(channel);
        return lowerIsBetter ? (stats.getMax() - x) / range : (x - stats.getMin()) / range;
    }
}
