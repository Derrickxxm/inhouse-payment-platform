package com.example.payplat.payment;

public record StatementLine(long orderId, long amountCents, String status) {}
