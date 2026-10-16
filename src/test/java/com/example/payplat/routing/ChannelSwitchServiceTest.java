package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** FASTEST ranking is [C, A, W, B]. */
@TestPropertySource(locations = "classpath:test-channels.properties")
class ChannelSwitchServiceTest extends RoutingTestBase {

    @Test
    void picksTheTopChannelWhenItAccepts() {
        assertThat(switchService.pickWithFailover(Tier.FASTEST, id -> true)).isEqualTo(C);
        assertThat(switchLog.entries()).isEmpty();
    }

    @Test
    void triesChannelsInTierOrderUntilOneAccepts() {
        List<String> tried = new ArrayList<>();
        String picked = switchService.pickWithFailover(Tier.FASTEST, id -> {
            tried.add(id);
            return id.equals(W);
        });
        assertThat(picked).isEqualTo(W);
        assertThat(tried).containsExactly(C, A, W);
    }

    @Test
    void logsEachSwitchWithReason() {
        switchService.pickWithFailover(Tier.FASTEST, id -> id.equals(W));
        assertThat(switchLog.entries()).containsExactly(
            new SwitchLog.Entry(C, A, "SUBMIT_FAILED"),
            new SwitchLog.Entry(A, W, "SUBMIT_FAILED"));
    }

    @Test
    void failuresAreRecordedAndEventuallyOpenTheCircuit() {
        for (int i = 0; i < 3; i++) {
            switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C));
        }
        assertThat(circuits.isOpen(C)).isTrue();
        List<String> tried = new ArrayList<>();
        switchService.pickWithFailover(Tier.FASTEST, id -> { tried.add(id); return true; });
        assertThat(tried).containsExactly(A);
    }

    @Test
    void successResetsTheFailureCounter() {
        switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C));
        switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C));
        switchService.pickWithFailover(Tier.FASTEST, id -> true);
        switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C));
        switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C));
        assertThat(circuits.isOpen(C)).isFalse();
    }

    @Test
    void pickConsumesAPermitAndRateLimitedChannelIsSkippedNextTime() {
        // A has 2 permits/sec; C always fails so A is picked twice, then excluded.
        assertThat(switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C))).isEqualTo(A);
        assertThat(switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C))).isEqualTo(A);
        assertThat(switchService.pickWithFailover(Tier.FASTEST, id -> !id.equals(C))).isEqualTo(W);
        assertThat(switchLog.entries()).last().isEqualTo(new SwitchLog.Entry(C, W, "SUBMIT_FAILED"));
    }

    @Test
    void throwsWhenEveryChannelRejects() {
        assertThatThrownBy(() -> switchService.pickWithFailover(Tier.FASTEST, id -> false))
            .isInstanceOf(NoEligibleChannelException.class);
        // every candidate counted one failure; final abandoned candidate has no switch entry
        assertThat(switchLog.entries()).hasSize(3);
    }

    @Test
    void throwsWhenNothingIsEligible() {
        for (String id : List.of(A, B, C, W)) {
            for (int i = 0; i < 3; i++) circuits.recordFailure(id);
        }
        assertThatThrownBy(() -> switchService.pickWithFailover(Tier.CHEAPEST, id -> true))
            .isInstanceOf(NoEligibleChannelException.class);
    }
}
