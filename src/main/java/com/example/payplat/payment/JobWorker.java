package com.example.payplat.payment;

import com.example.payplat.routing.ChannelSwitchService;
import com.example.payplat.routing.NoEligibleChannelException;
import com.example.payplat.routing.Tier;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobWorker {
    private final JdbcTemplate jdbc;
    private final ChannelSwitchService switches;
    private final ChannelGateway gateway;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JobWorker(JdbcTemplate jdbc, ChannelSwitchService switches, ChannelGateway gateway,
                     Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.switches = switches;
        this.gateway = gateway;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runOnce() {
        int claimed = 0;
        for (long jobId : jdbc.queryForList("SELECT id FROM job WHERE status = 'PENDING' ORDER BY id", Long.class)) {
            if (jdbc.update("UPDATE job SET status = 'RUNNING' WHERE id = ? AND status = 'PENDING'", jobId) != 1) continue;
            claimed++;
            process(jobId);
        }
        return claimed;
    }

    private void process(long jobId) {
        Work order = jdbc.queryForObject("SELECT o.*, t.tier FROM payment_order o "
            + "JOIN task t ON t.id = o.task_id JOIN job j ON j.order_id = o.id WHERE j.id = ?", (rs, n) ->
            new Work(rs.getLong("id"), rs.getLong("task_id"), rs.getLong("amount_cents"),
                OrderStatus.valueOf(rs.getString("status")), Tier.valueOf(rs.getString("tier"))), jobId);
        if (order.status() != OrderStatus.CREATED) {
            jdbc.update("UPDATE job SET status = 'DONE' WHERE id = ?", jobId);
            return;
        }
        String channel = null;
        OrderStatus status;
        String reason = null;
        try {
            channel = switches.pickWithFailover(order.tier(), order.taskId(), order.id(), id -> {
                jdbc.update("UPDATE payment_order SET attempts = attempts + 1, updated_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), order.id());
                return gateway.submit(id, order.id(), order.amount()).isAccepted();
            });
            status = OrderStatus.SUBMITTED;
        } catch (NoEligibleChannelException e) {
            status = OrderStatus.FAILED;
            reason = "NO_ELIGIBLE_CHANNEL";
        }
        String selected = channel;
        OrderStatus result = status;
        String failure = reason;
        transaction.executeWithoutResult(tx -> {
            jdbc.update("UPDATE payment_order SET status = ?, channel_id = ?, reason = ?, updated_at = ? WHERE id = ?",
                result.name(), selected, failure, Timestamp.from(clock.instant()), order.id());
            jdbc.update("UPDATE job SET status = 'DONE' WHERE id = ?", jobId);
        });
    }

    private record Work(long id, long taskId, long amount, OrderStatus status, Tier tier) {}
}
