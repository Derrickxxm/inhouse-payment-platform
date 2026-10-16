package com.example.payplat.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class DemoClock extends Clock {
    private Instant now = Instant.parse("2026-01-15T10:00:00Z");
    public void set(Instant instant) { now = instant; }
    public void advance(Duration duration) { now = now.plus(duration); }
    @Override public Instant instant() { return now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
}
