package com.example.payplat.payment;

import java.time.Instant;

public record ChannelCallback(String channelId, long orderId, String status, long sequence, Instant sentAt) {}
