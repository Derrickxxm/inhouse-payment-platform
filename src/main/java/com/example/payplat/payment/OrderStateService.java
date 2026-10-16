package com.example.payplat.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderStateService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrderStateService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ApplyResult apply(long orderId, OrderStatus newStatus) {
        return apply(orderId, newStatus, "DIRECT");
    }

    @Transactional
    public ApplyResult apply(long orderId, OrderStatus newStatus, String source) {
        while (true) {
            var statuses = jdbc.queryForList("SELECT status FROM payment_order WHERE id = ?", String.class, orderId);
            if (statuses.isEmpty()) throw new NoSuchElementException("order " + orderId);
            OrderStatus from = OrderStatus.valueOf(statuses.getFirst());
            ApplyResult result = from == newStatus ? ApplyResult.DUPLICATE
                : legal(from, newStatus) ? ApplyResult.APPLIED : ApplyResult.IGNORED_ILLEGAL;
            Timestamp now = Timestamp.from(clock.instant());
            if (result == ApplyResult.APPLIED && jdbc.update(
                "UPDATE payment_order SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                newStatus.name(), now, orderId, from.name()) == 0) continue;
            jdbc.update("INSERT INTO state_event (order_id, from_status, to_status, outcome, source, at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", orderId, from.name(),
                newStatus == null ? null : newStatus.name(), result.name(), source, now);
            return result;
        }
    }

    private boolean legal(OrderStatus from, OrderStatus to) {
        return switch (from) {
            case CREATED -> to == OrderStatus.SUBMITTED;
            case SUBMITTED -> to == OrderStatus.SUCCESS || to == OrderStatus.FAILED || to == OrderStatus.UNKNOWN;
            case UNKNOWN -> to == OrderStatus.SUCCESS || to == OrderStatus.FAILED;
            case SUCCESS, FAILED -> false;
        };
    }
}
