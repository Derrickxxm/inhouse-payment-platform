package com.example.payplat.payment;

public record OrderView(long orderId, String channelId, long amountCents, OrderStatus status,
                        int attempts, String reason) {}
