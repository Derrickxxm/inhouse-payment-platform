package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A 5th channel (in maintenance) is added by properties only: no code change. */
@TestPropertySource(locations = "classpath:test-channels-5.properties")
class ExtraChannelTest extends RoutingTestBase {

    @Test
    void fifthChannelIsBoundFromProperties() {
        assertThat(registry.all()).hasSize(5);
        assertThat(registry.byId("FakeBankD").orElseThrow().maintenance()).isTrue();
    }

    @Test
    void maintenanceChannelIsExcludedFromEveryTier() {
        for (Tier t : Tier.values()) {
            assertThat(router.rank(t)).as(t.name()).doesNotContain("FakeBankD").hasSize(4);
        }
    }

    @Test
    void maintenanceChannelDoesNotShiftTheOrderOfOthers() {
        // D is the best at everything but in maintenance: normalization must not change the rest.
        assertThat(router.rank(Tier.BALANCED)).containsExactly(W, A, C, B);
    }
}
