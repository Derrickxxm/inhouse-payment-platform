package com.example.payplat.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecoveryService {
    private final JdbcTemplate jdbc;
    private final ChannelGateway gateway;
    private final OrderStateService states;
    private final TaskService tasks;
    private final Clock clock;
    private final long windowSeconds;
    private final TransactionTemplate transaction;

    public RecoveryService(JdbcTemplate jdbc, ChannelGateway gateway, OrderStateService states,
                           TaskService tasks, Clock clock, PlatformTransactionManager manager,
                           @Value("${payplat.recovery.window-seconds:120}") long windowSeconds) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.states = states;
        this.tasks = tasks;
        this.clock = clock;
        this.windowSeconds = windowSeconds;
        this.transaction = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RecoveryReport recover() {
        Timestamp cutoff = Timestamp.from(clock.instant().minusSeconds(windowSeconds));
        var candidates = jdbc.query("SELECT o.id, o.task_id, o.channel_id, o.status FROM payment_order o "
            + "WHERE ((o.status = 'CREATED' AND EXISTS (SELECT 1 FROM job j WHERE j.order_id = o.id "
            + "AND j.status = 'RUNNING' AND j.started_at < ?)) "
            + "OR (o.status IN ('SUBMITTED', 'UNKNOWN') AND o.updated_at < ?)) "
            // Unchanged candidates must also wait a window before being queried again, across restarts.
            + "AND NOT EXISTS (SELECT 1 FROM recovery_event r WHERE r.order_id = o.id AND r.at >= ?) ORDER BY o.id",
            (rs, n) -> new Candidate(rs.getLong("id"), rs.getLong("task_id"), rs.getString("channel_id"),
                OrderStatus.valueOf(rs.getString("status"))), cutoff, cutoff, cutoff);
        int requeued = 0, resolved = 0, untouched = 0;
        Set<Long> changedTasks = new HashSet<>();
        for (Candidate order : candidates) {
            ChannelQueryResult answer = order.status() == OrderStatus.CREATED && order.channelId() == null
                ? null : gateway.query(order.channelId(), order.id());
            String action = transaction.execute(tx -> repair(order, answer, changedTasks));
            switch (action) {
                case "REQUEUED" -> requeued++;
                case "RESOLVED" -> resolved++;
                default -> untouched++;
            }
        }
        changedTasks.forEach(tasks::refresh);
        return new RecoveryReport(requeued, resolved, untouched);
    }

    private String repair(Candidate order, ChannelQueryResult answer, Set<Long> changedTasks) {
        String action = "UNTOUCHED";
        boolean changed = false;
        if (order.status() == OrderStatus.CREATED) {
            if (answer == null || answer == ChannelQueryResult.NOT_FOUND) {
                if (jdbc.update("UPDATE job SET status = 'PENDING' WHERE order_id = ? AND status = 'RUNNING'",
                    order.id()) > 0) action = "REQUEUED";
            } else {
                changed = states.apply(order.id(), OrderStatus.SUBMITTED, "RECOVERY") == ApplyResult.APPLIED;
                if (answer != ChannelQueryResult.PROCESSING) {
                    changed |= states.apply(order.id(), OrderStatus.valueOf(answer.name()), "RECOVERY") == ApplyResult.APPLIED;
                }
                jdbc.update("UPDATE job SET status = 'DONE' WHERE order_id = ? AND status = 'RUNNING'", order.id());
                action = "RESOLVED";
            }
        } else {
            OrderStatus target = switch (answer) {
                case SUCCESS -> OrderStatus.SUCCESS;
                case FAILED -> OrderStatus.FAILED;
                case NOT_FOUND -> order.status() == OrderStatus.SUBMITTED ? OrderStatus.UNKNOWN : null;
                case PROCESSING -> null;
            };
            if (target != null) {
                changed = states.apply(order.id(), target, "RECOVERY") == ApplyResult.APPLIED;
                if (changed) action = "RESOLVED";
            }
        }
        jdbc.update("INSERT INTO recovery_event (order_id, action, channel_answer, at) VALUES (?, ?, ?, ?)",
            order.id(), action, answer == null ? null : answer.name(), Timestamp.from(clock.instant()));
        if (changed) changedTasks.add(order.taskId());
        return action;
    }

    private record Candidate(long id, long taskId, String channelId, OrderStatus status) {}
}
