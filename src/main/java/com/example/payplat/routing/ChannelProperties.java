package com.example.payplat.routing;

public record ChannelProperties(String id, int feeBps, int latencyMs, double stability,
                                double successRate, int rateLimitPerSec, boolean maintenance) {}
