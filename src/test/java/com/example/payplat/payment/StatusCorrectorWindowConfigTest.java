package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "payplat.corrector.window-seconds=10")
class StatusCorrectorWindowConfigTest extends Pp3TestBase {

    @Test
    void windowIsConfigurable() {
        TaskView v = submitted("win-1", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.SUCCESS);
        clock.advance(Duration.ofSeconds(9));
        assertThat(corrector.runOnce()).isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(corrector.runOnce()).isEqualTo(1);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
    }
}
