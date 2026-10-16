package com.example.payplat.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Mutable clock for tests. Starts at 100 ms into a second so +500 ms stays in the same second. */
public class TestClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00.100Z");

    public synchronized void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public synchronized Instant instant() { return now; }
}
