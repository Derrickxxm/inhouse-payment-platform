package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(locations = "classpath:test-channels.properties")
class RateLimiterRegistryTest extends RoutingTestBase {

    @Test
    void allowsExactlyRateLimitPerSecPermits() {
        assertThat(limiter.tryAcquire(A)).isTrue();
        assertThat(limiter.tryAcquire(A)).isTrue();
        assertThat(limiter.tryAcquire(A)).isFalse();
    }

    @Test
    void isExhaustedIsNonConsuming() {
        assertThat(limiter.isExhausted(A)).isFalse();
        assertThat(limiter.isExhausted(A)).isFalse();
        limiter.tryAcquire(A);
        limiter.tryAcquire(A);
        assertThat(limiter.isExhausted(A)).isTrue();
    }

    @Test
    void sameSecondStaysExhaustedAfterHalfASecond() {
        limiter.tryAcquire(A);
        limiter.tryAcquire(A);
        clock.advance(Duration.ofMillis(500));
        assertThat(limiter.tryAcquire(A)).isFalse();
    }

    @Test
    void newSecondStartsFreshWindow() {
        limiter.tryAcquire(A);
        limiter.tryAcquire(A);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire(A)).isTrue();
    }

    @Test
    void channelsAreLimitedIndependently() {
        limiter.tryAcquire(A);
        limiter.tryAcquire(A);
        assertThat(limiter.tryAcquire(B)).isTrue();
    }
}
