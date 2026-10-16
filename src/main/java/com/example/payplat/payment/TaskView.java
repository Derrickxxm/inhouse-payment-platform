package com.example.payplat.payment;

import java.util.List;

public record TaskView(long taskId, String bizRequestId, TaskStatus status, List<OrderView> orders, String reason) {
    public TaskView {
        orders = List.copyOf(orders);
    }
}
