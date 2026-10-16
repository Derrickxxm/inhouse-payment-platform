package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payplat.routing.Tier;
import org.junit.jupiter.api.Test;

class DebitRequestTest {
    @Test
    void validRequestIsAccepted() {
        DebitRequest r = new DebitRequest("b1", "u1", 100, Tier.CHEAPEST, 5);
        assertThat(r.amountCents()).isEqualTo(100);
        assertThat(r.splitInto()).isEqualTo(5);
    }

    @Test
    void nonPositiveAmountRejected() {
        assertThatThrownBy(() -> new DebitRequest("b1", "u1", 0, Tier.CHEAPEST, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebitRequest("b1", "u1", -5, Tier.CHEAPEST, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitOutsideOneToFiveRejected() {
        assertThatThrownBy(() -> new DebitRequest("b1", "u1", 100, Tier.FASTEST, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebitRequest("b1", "u1", 100, Tier.FASTEST, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankIdsNullTierAndTooSmallAmountRejected() {
        assertThatThrownBy(() -> new DebitRequest(" ", "u1", 100, Tier.FASTEST, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebitRequest(null, "u1", 100, Tier.FASTEST, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebitRequest("b1", "", 100, Tier.FASTEST, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebitRequest("b1", "u1", 100, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebitRequest("b1", "u1", 3, Tier.FASTEST, 4)).isInstanceOf(IllegalArgumentException.class);
    }
}
