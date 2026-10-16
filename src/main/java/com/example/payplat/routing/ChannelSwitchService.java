package com.example.payplat.routing;

import java.util.function.Predicate;
import org.springframework.stereotype.Service;

@Service
public class ChannelSwitchService {
    private final Router router;
    private final RateLimiterRegistry limiter;
    private final CircuitBreakerRegistry circuits;
    private final SwitchLog log;

    public ChannelSwitchService(Router router, RateLimiterRegistry limiter,
                                CircuitBreakerRegistry circuits, SwitchLog log) {
        this.router = router;
        this.limiter = limiter;
        this.circuits = circuits;
        this.log = log;
    }

    public String pickWithFailover(Tier tier, Predicate<String> trySubmit) {
        return pickWithFailover(tier, null, null, trySubmit);
    }

    public String pickWithFailover(Tier tier, Long taskId, Long orderId, Predicate<String> trySubmit) {
        String abandoned = null;
        String reason = null;
        for (String id : router.rank(tier)) {
            if (abandoned != null) log.record(taskId, orderId, abandoned, id, reason);
            if (!limiter.tryAcquire(id)) {
                reason = "RATE_LIMITED";
            } else if (trySubmit.test(id)) {
                circuits.recordSuccess(id);
                return id;
            } else {
                circuits.recordFailure(id);
                reason = "SUBMIT_FAILED";
            }
            abandoned = id;
        }
        throw new NoEligibleChannelException();
    }
}
