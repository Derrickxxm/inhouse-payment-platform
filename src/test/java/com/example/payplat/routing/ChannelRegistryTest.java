package com.example.payplat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(locations = "classpath:test-channels.properties")
class ChannelRegistryTest extends RoutingTestBase {

    @Test
    void bindsFourChannelsInConfigOrder() {
        List<String> ids = registry.all().stream().map(ChannelProperties::id).toList();
        assertThat(ids).containsExactly(A, B, C, W);
    }

    @Test
    void bindsAllFields() {
        ChannelProperties a = registry.byId(A).orElseThrow();
        assertThat(a.feeBps()).isEqualTo(80);
        assertThat(a.latencyMs()).isEqualTo(300);
        assertThat(a.stability()).isEqualTo(0.95);
        assertThat(a.successRate()).isEqualTo(0.97);
        assertThat(a.rateLimitPerSec()).isEqualTo(2);
        assertThat(a.maintenance()).isFalse();
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(registry.byId("NoSuchChannel")).isEmpty();
    }

    @Test
    void tierEnumHasExactlyTheFourTiers() {
        assertThat(Tier.values()).containsExactly(
            Tier.CHEAPEST, Tier.FASTEST, Tier.MOST_RELIABLE, Tier.BALANCED);
    }
}
