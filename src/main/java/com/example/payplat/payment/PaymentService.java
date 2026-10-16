package com.example.payplat.payment;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PaymentService(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    public TaskView submit(DebitRequest request) {
        Long existing = findTask(request.bizRequestId());
        if (existing != null) return get(existing);
        try {
            return transaction.execute(tx -> {
                Timestamp now = Timestamp.from(clock.instant());
                long taskId = insert("INSERT INTO task "
                    + "(biz_request_id, user_id, amount_cents, tier, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    request.bizRequestId(), request.userId(), request.amountCents(), request.tier().name(),
                    TaskStatus.ACCEPTED.name(), now);
                long amount = request.amountCents() / request.splitInto();
                for (int i = 0; i < request.splitInto(); i++) {
                    long slice = amount + (i == request.splitInto() - 1 ? request.amountCents() % request.splitInto() : 0);
                    long orderId = insert("INSERT INTO payment_order "
                        + "(task_id, amount_cents, status, attempts, updated_at) VALUES (?, ?, ?, 0, ?)",
                        taskId, slice, OrderStatus.CREATED.name(), now);
                    jdbc.update("INSERT INTO job (order_id, status, created_at) VALUES (?, 'PENDING', ?)", orderId, now);
                }
                return get(taskId);
            });
        } catch (DuplicateKeyException e) {
            // The failed transaction has rolled back; the unique-key winner has committed.
            Long winner = findTask(request.bizRequestId());
            if (winner == null) throw e;
            return get(winner);
        }
    }

    public TaskView get(long taskId) {
        List<TaskView> tasks = jdbc.query("SELECT * FROM task WHERE id = ?", (rs, n) ->
            new TaskView(rs.getLong("id"), rs.getString("biz_request_id"),
                TaskStatus.valueOf(rs.getString("status")), List.of(), rs.getString("reason")), taskId);
        if (tasks.isEmpty()) throw new NoSuchElementException("task " + taskId);
        TaskView task = tasks.getFirst();
        List<OrderView> orders = jdbc.query("SELECT * FROM payment_order WHERE task_id = ? ORDER BY id", (rs, n) ->
            new OrderView(rs.getLong("id"), rs.getString("channel_id"), rs.getLong("amount_cents"),
                OrderStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getString("reason")), taskId);
        return new TaskView(task.taskId(), task.bizRequestId(), task.status(), orders, task.reason());
    }

    private Long findTask(String bizRequestId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM task WHERE biz_request_id = ?", Long.class, bizRequestId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long insert(String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[] {"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
