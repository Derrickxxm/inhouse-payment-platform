package com.example.payplat.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final PaymentService payments;

    public TaskService(JdbcTemplate jdbc, PaymentService payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @Transactional
    public TaskView refresh(long taskId) {
        TaskView task = payments.get(taskId);
        int total = task.orders().size();
        long success = task.orders().stream().filter(o -> o.status() == OrderStatus.SUCCESS).count();
        long failed = task.orders().stream().filter(o -> o.status() == OrderStatus.FAILED).count();
        TaskStatus status = TaskStatus.PROCESSING;
        String reason = null;
        if (success == total) status = TaskStatus.SUCCEEDED;
        else if (failed == total) status = TaskStatus.FAILED;
        else if (success > 0 && failed > 0 && success + failed == total) {
            status = TaskStatus.NOT_FINALIZED;
            reason = "partial: " + success + "/" + total + " succeeded";
        }
        jdbc.update("UPDATE task SET status = ?, reason = ? WHERE id = ?", status.name(), reason, taskId);
        return payments.get(taskId);
    }
}
