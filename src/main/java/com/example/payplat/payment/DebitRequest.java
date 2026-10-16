package com.example.payplat.payment;

import com.example.payplat.routing.Tier;

public record DebitRequest(String bizRequestId, String userId, long amountCents, Tier tier, int splitInto) {
    public DebitRequest {
        if (bizRequestId == null || bizRequestId.isBlank() || userId == null || userId.isBlank()
            || tier == null || amountCents <= 0 || splitInto < 1 || splitInto > 5 || amountCents < splitInto) {
            throw new IllegalArgumentException("invalid debit request");
        }
    }
}
