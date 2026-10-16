package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(locations = "classpath:test-channels.properties")
class CircuitBreakerRegistryTest extends RoutingTestBase {

    @Test
    void closedByDefault() {
        assertThat(circuits.isOpen(A)).isFalse();
    }

    @Test
    void twoFailuresDoNotOpen() {
        circuits.recordFailure(A);
        circuits.recordFailure(A);
        assertThat(circuits.isOpen(A)).isFalse();
    }

    @Test
    void opensAfterThreeConsecutiveFailures() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(A);
        assertThat(circuits.isOpen(A)).isTrue();
    }

    @Test
    void successResetsTheConsecutiveCounter() {
        circuits.recordFailure(A);
        circuits.recordFailure(A);
        circuits.recordSuccess(A);
        circuits.recordFailure(A);
        circuits.recordFailure(A);
        assertThat(circuits.isOpen(A)).isFalse();
    }

    @Test
    void stillOpenJustBeforeThirtySeconds() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(A);
        clock.advance(Duration.ofSeconds(29));
        assertThat(circuits.isOpen(A)).isTrue();
    }

    @Test
    void halfOpenAtThirtySeconds() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(A);
        clock.advance(Duration.ofSeconds(30));
        assertThat(circuits.isOpen(A)).isFalse();
    }

    @Test
    void failureWhileHalfOpenReopensForAnotherThirtySeconds() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(A);
        clock.advance(Duration.ofSeconds(30));
        circuits.recordFailure(A);
        assertThat(circuits.isOpen(A)).isTrue();
        clock.advance(Duration.ofSeconds(29));
        assertThat(circuits.isOpen(A)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(circuits.isOpen(A)).isFalse();
    }

    @Test
    void successWhileHalfOpenClosesAndResets() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(A);
        clock.advance(Duration.ofSeconds(30));
        circuits.recordSuccess(A);
        circuits.recordFailure(A);
        assertThat(circuits.isOpen(A)).isFalse();
    }

    @Test
    void circuitsArePerChannel() {
        for (int i = 0; i < 3; i++) circuits.recordFailure(A);
        assertThat(circuits.isOpen(B)).isFalse();
    }
}
