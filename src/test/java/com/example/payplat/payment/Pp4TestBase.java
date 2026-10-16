package com.example.payplat.payment;

import com.example.payplat.routing.Tier;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Shared set-up for PP-4 in-memory tests. The startup hook is off; tests call recover() themselves. */
@TestPropertySource(properties = "payplat.recovery.on-startup=false")
abstract class Pp4TestBase extends Pp3TestBase {
    @Autowired RecoveryService recovery;

    /** Submits a one-order task (CHEAPEST) without running the worker; returns the order id. */
    long freshOrder(String bizId) {
        TaskView v = payments.submit(req(bizId, 500, Tier.CHEAPEST, 1));
        return v.orders().get(0).orderId();
    }

    /** Leaves the order exactly as a crash after the marker but before the post-submit transaction would. */
    void stuckInFlight(long orderId, String channel) {
        jdbc.update("update job set status = 'RUNNING', started_at = ? where order_id = ?",
            Timestamp.from(clock.instant()), orderId);
        jdbc.update("update payment_order set channel_id = ?, submitting_at = ?, attempts = 1 where id = ?",
            channel, Timestamp.from(clock.instant()), orderId);
    }

    void pastWindow() { clock.advance(Duration.ofSeconds(121)); }

    String jobStatus(long orderId) {
        return jdbc.queryForObject("select status from job where order_id = ?", String.class, orderId);
    }

    long taskIdOf(long orderId) {
        return jdbc.queryForObject("select task_id from payment_order where id = ?", Long.class, orderId);
    }

    List<String> eventSources(long orderId) {
        return jdbc.queryForList("select source from state_event where order_id = ? order by id", String.class, orderId);
    }

    long recoveryEvents() { return count("recovery_event"); }
}
