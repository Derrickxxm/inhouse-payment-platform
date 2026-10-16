package com.example.payplat.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatusCorrector {
    private final JdbcTemplate jdbc;
    private final ChannelGateway gateway;
    private final OrderStateService states;
    private final TaskService tasks;
    private final Clock clock;
    private final long windowSeconds;

    public StatusCorrector(JdbcTemplate jdbc, ChannelGateway gateway, OrderStateService states,
                           TaskService tasks, Clock clock,
                           @Value("${payplat.corrector.window-seconds:60}") long windowSeconds) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.states = states;
        this.tasks = tasks;
        this.clock = clock;
        this.windowSeconds = windowSeconds;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runOnce() {
        var candidates = jdbc.query("SELECT id, task_id, channel_id, status FROM payment_order "
            + "WHERE status IN ('SUBMITTED', 'UNKNOWN') AND updated_at < ? ORDER BY id",
            (rs, n) -> new Candidate(rs.getLong("id"), rs.getLong("task_id"), rs.getString("channel_id"),
                OrderStatus.valueOf(rs.getString("status"))),
            Timestamp.from(clock.instant().minusSeconds(windowSeconds)));
        Set<Long> changedTasks = new HashSet<>();
        for (Candidate order : candidates) {
            ChannelQueryResult answer = gateway.query(order.channelId(), order.id());
            OrderStatus target = switch (answer) {
                case SUCCESS -> OrderStatus.SUCCESS;
                case FAILED -> OrderStatus.FAILED;
                case PROCESSING -> null;
                case NOT_FOUND -> order.status() == OrderStatus.SUBMITTED ? OrderStatus.UNKNOWN : null;
            };
            if (target != null && states.apply(order.id(), target, "QUERY") == ApplyResult.APPLIED) {
                changedTasks.add(order.taskId());
            }
        }
        changedTasks.forEach(tasks::refresh);
        return candidates.size();
    }

    private record Candidate(long id, long taskId, String channelId, OrderStatus status) {}
}
