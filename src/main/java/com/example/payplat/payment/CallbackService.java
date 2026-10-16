package com.example.payplat.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CallbackService {
    private static final Set<String> STATUSES = Set.of("SUCCESS", "FAILED", "UNKNOWN", "PROCESSING");
    private final JdbcTemplate jdbc;
    private final OrderStateService states;
    private final TaskService tasks;
    private final Clock clock;

    public CallbackService(JdbcTemplate jdbc, OrderStateService states, TaskService tasks, Clock clock) {
        this.jdbc = jdbc;
        this.states = states;
        this.tasks = tasks;
        this.clock = clock;
    }

    @Transactional
    public CallbackOutcome onCallback(ChannelCallback callback) {
        CallbackOutcome outcome = decide(callback);
        jdbc.update("INSERT INTO callback_log "
            + "(channel_id, order_id, status, sequence, sent_at, outcome, received_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            callback == null ? null : callback.channelId(), callback == null ? null : callback.orderId(),
            callback == null ? null : callback.status(), callback == null ? null : callback.sequence(),
            callback == null || callback.sentAt() == null ? null : Timestamp.from(callback.sentAt()),
            outcome.name(), Timestamp.from(clock.instant()));
        return outcome;
    }

    private CallbackOutcome decide(ChannelCallback callback) {
        if (callback == null) return CallbackOutcome.IGNORED_ILLEGAL;
        // Hold the order lock through log insertion so concurrent callbacks see the committed sequence.
        var orders = jdbc.query("SELECT task_id, channel_id FROM payment_order WHERE id = ? FOR UPDATE",
            (rs, n) -> new CallbackOrder(rs.getLong("task_id"), rs.getString("channel_id")), callback.orderId());
        if (orders.isEmpty() || callback.status() == null || !STATUSES.contains(callback.status())) {
            return CallbackOutcome.IGNORED_ILLEGAL;
        }
        CallbackOrder order = orders.getFirst();
        if (order.channelId() == null || !order.channelId().equals(callback.channelId())) {
            return CallbackOutcome.IGNORED_ILLEGAL;
        }
        Long last = jdbc.queryForObject("SELECT MAX(sequence) FROM callback_log WHERE order_id = ? "
            + "AND outcome IN ('APPLIED', 'DUPLICATE', 'NOOP')", Long.class, callback.orderId());
        if (last != null) {
            if (callback.sequence() < last) return CallbackOutcome.IGNORED_STALE;
            if (callback.sequence() == last) return CallbackOutcome.DUPLICATE;
        }
        if (callback.status().equals("PROCESSING")) return CallbackOutcome.NOOP;
        ApplyResult result = states.apply(callback.orderId(), OrderStatus.valueOf(callback.status()), "CALLBACK");
        if (result == ApplyResult.APPLIED) tasks.refresh(order.taskId());
        return CallbackOutcome.valueOf(result.name());
    }

    private record CallbackOrder(long taskId, String channelId) {}
}
