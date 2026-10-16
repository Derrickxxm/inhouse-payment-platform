package com.example.payplat.payment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The "outside world" of the crash tests: JVM-static so it survives a Spring context restart. */
final class ChannelWorld {
    private static final Map<Long, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private static final Map<Long, AtomicInteger> accepted = new ConcurrentHashMap<>();
    private static final AtomicInteger queries = new AtomicInteger();
    private static volatile long crashAfterAccept = -1;
    private static volatile long crashBeforeAccept = -1;
    private static volatile ChannelQueryResult acceptedAnswer = ChannelQueryResult.PROCESSING;

    private ChannelWorld() {}

    static void reset() {
        invocations.clear();
        accepted.clear();
        queries.set(0);
        crashAfterAccept = -1;
        crashBeforeAccept = -1;
        acceptedAnswer = ChannelQueryResult.PROCESSING;
    }

    /** One-shot: the next submit for this order is accepted by the channel and then the "process dies". */
    static void crashAfterAccept(long orderId) { crashAfterAccept = orderId; }

    /** One-shot: the process dies inside the gateway before the channel ever sees the order. */
    static void crashBeforeAccept(long orderId) { crashBeforeAccept = orderId; }

    static void acceptedAnswer(ChannelQueryResult r) { acceptedAnswer = r; }

    static int invocations(long orderId) { return invocations.getOrDefault(orderId, new AtomicInteger()).get(); }
    static int accepted(long orderId) { return accepted.getOrDefault(orderId, new AtomicInteger()).get(); }
    static int queries() { return queries.get(); }

    static void submit(long orderId) {
        invocations.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
        if (crashBeforeAccept == orderId) {
            crashBeforeAccept = -1;
            throw new SimulatedCrash("before accept, order " + orderId);
        }
        accepted.computeIfAbsent(orderId, k -> new AtomicInteger()).incrementAndGet();
        if (crashAfterAccept == orderId) {
            crashAfterAccept = -1;
            throw new SimulatedCrash("after accept, order " + orderId);
        }
    }

    static ChannelQueryResult query(long orderId) {
        queries.incrementAndGet();
        return accepted(orderId) > 0 ? acceptedAnswer : ChannelQueryResult.NOT_FOUND;
    }

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) { super(message); }
    }
}
