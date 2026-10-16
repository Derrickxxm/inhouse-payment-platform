package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(locations = "classpath:test-channels-maint.properties")
class MaintenanceWorkerTest extends PaymentTestBase {
    @Test
    void noEligibleChannelFailsOrderWithoutCallingGateway() {
        TaskView v = payments.submit(req("m-1", 300, Tier.BALANCED, 1));
        worker.runOnce();
        OrderView o = payments.get(v.taskId()).orders().get(0);
        assertThat(o.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(o.reason()).isEqualTo("NO_ELIGIBLE_CHANNEL");
        assertThat(gateway.calls()).isEmpty();
    }
}
