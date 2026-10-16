package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(locations = "classpath:test-channels.properties")
class RouterTest extends RoutingTestBase {

    @Test
    void cheapestOrdersByFeeAscending() {
        assertThat(router.rank(Tier.CHEAPEST)).containsExactly(B, W, A, C);
    }

    @Test
    void fastestOrdersByLatencyAscending() {
        assertThat(router.rank(Tier.FASTEST)).containsExactly(C, A, W, B);
    }

    @Test
    void mostReliableOrdersBySuccessRateTimesStabilityDescending() {
        assertThat(router.rank(Tier.MOST_RELIABLE)).containsExactly(W, A, C, B);
    }

    @Test
    void balancedUsesEqualWeightMinMaxNormalizedScore() {
        // hand-computed scores: W 3.04, A 2.76, C 1.86, B 1.00
        assertThat(router.rank(Tier.BALANCED)).containsExactly(W, A, C, B);
    }

    @Test
    void rankDoesNotConsumeRateLimitPermits() {
        for (int i = 0; i < 5; i++) router.rank(Tier.FASTEST);
        assertThat(limiter.tryAcquire(A)).isTrue();
    }

    @Test
    void rateLimitedChannelIsExcludedWithinTheSameSecond() {
        assertThat(limiter.tryAcquire(A)).isTrue();
        assertThat(limiter.tryAcquire(A)).isTrue();
        assertThat(router.rank(Tier.FASTEST)).containsExactly(C, W, B);
    }

    @Test
    void rateLimitedChannelRecoversInTheNextSecond() {
        limiter.tryAcquire(A);
        limiter.tryAcquire(A);
        clock.advance(java.time.Duration.ofSeconds(1));
        assertThat(router.rank(Tier.FASTEST)).containsExactly(C, A, W, B);
    }

    @Test
    void openCircuitExcludesChannel() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(C);
        assertThat(router.rank(Tier.FASTEST)).containsExactly(A, W, B);
    }

    @Test
    void channelReturnsAfterCircuitHalfOpens() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(C);
        clock.advance(java.time.Duration.ofSeconds(30));
        assertThat(router.rank(Tier.FASTEST)).containsExactly(C, A, W, B);
    }
}
