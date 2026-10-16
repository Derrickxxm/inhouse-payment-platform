package com.example.payplat.payment;

import com.example.payplat.routing.TestClock;
import com.example.payplat.routing.Tier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/** Shared set-up for PP-3 tests. CHEAPEST routes to FakeBankB in test-channels.properties. */
abstract class Pp3TestBase extends PaymentTestBase {
    @Autowired CallbackService callbacks;
    @Autowired StatusCorrector corrector;
    @Autowired TestClock clock;

    /** Submits a task of {@code split} orders and runs the worker so every order is SUBMITTED on a channel. */
    TaskView submitted(String bizId, int split) {
        TaskView v = payments.submit(req(bizId, 900, Tier.CHEAPEST, split));
        worker.runOnce();
        return payments.get(v.taskId());
    }

    ChannelCallback cb(OrderView o, String status, long seq) {
        return new ChannelCallback(o.channelId(), o.orderId(), status, seq, Instant.parse("2026-01-01T00:00:00Z"));
    }

    TaskView task(long taskId) { return payments.get(taskId); }

    List<Map<String, Object>> stateEvents(long orderId) {
        return jdbc.queryForList("select * from state_event where order_id = ? order by id", orderId);
    }

    long appliedEvents(long orderId, String to) {
        return jdbc.queryForObject("select count(*) from state_event where order_id = ? and to_status = ? and outcome = 'APPLIED'",
            Long.class, orderId, to);
    }

    List<String> callbackOutcomes(long orderId) {
        return jdbc.queryForList("select outcome from callback_log where order_id = ? order by id", String.class, orderId);
    }
}
