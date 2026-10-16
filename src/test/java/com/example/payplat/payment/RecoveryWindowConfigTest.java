package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"payplat.recovery.window-seconds=10", "payplat.recovery.on-startup=false"})
class RecoveryWindowConfigTest extends Pp4TestBase {

    @Test
    void windowIsConfigurable() {
        long o = freshOrder("recwin-1");
        stuckInFlight(o, B);
        gateway.answer(o, ChannelQueryResult.NOT_FOUND);
        clock.advance(Duration.ofSeconds(10));
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 0));
        clock.advance(Duration.ofSeconds(1));
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(1, 0, 0));
    }
}
