package com.example.payplat.payment;

import java.util.Map;

public record ChannelStats(long attempts, long successes, double successRate,
                           long p50Ms, long p95Ms, Map<String, Long> failureReasons) {}
