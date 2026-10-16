package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Two channels with identical values, listed B first: ties break by id ascending in every tier. */
@TestPropertySource(locations = "classpath:test-channels-ties.properties")
class TieBreakTest extends RoutingTestBase {

    @Test
    void tiesAreBrokenByIdAscendingInEveryTier() {
        for (Tier t : Tier.values()) {
            assertThat(router.rank(t)).as(t.name()).containsExactly(A, B);
        }
    }
}
