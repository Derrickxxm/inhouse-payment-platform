package com.example.payplat.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderStateService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrderStateService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void apply(long orderId, OrderStatus newStatus) {
        if (jdbc.update("UPDATE payment_order SET status = ?, updated_at = ? WHERE id = ?",
            newStatus.name(), Timestamp.from(clock.instant()), orderId) == 0) {
            throw new NoSuchElementException("order " + orderId);
        }
    }
}
