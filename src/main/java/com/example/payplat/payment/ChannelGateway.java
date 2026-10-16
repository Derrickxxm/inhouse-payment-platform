package com.example.payplat.payment;

public interface ChannelGateway {
    SubmitResult submit(String channelId, long orderId, long amountCents);
    ChannelQueryResult query(String channelId, long orderId);
}
