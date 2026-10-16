package com.example.payplat.demo;

import com.example.payplat.payment.*;
import com.example.payplat.routing.ChannelRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Synthetic bank truth is independent of the application's database, including across a restart. */
public class DemoGateway implements ChannelGateway {
    private final ChannelRegistry registry;
    private final DemoClock clock;
    private Ledger ledger = new Ledger();
    private final Map<String, String> rejected = new HashMap<>();
    private final Map<String, Integer> rejectEvery = new HashMap<>();
    private final Map<String, Integer> channelCalls = new HashMap<>();
    private final Map<String, UnaryOperator<List<StatementLine>>> tampering = new HashMap<>();
    private Long crashOrder;

    public DemoGateway(ChannelRegistry registry, DemoClock clock) { this.registry = registry; this.clock = clock; }
    static final class Ledger {
        final Map<Key, Entry> entries = new LinkedHashMap<>();
        final Map<Key, Integer> calls = new HashMap<>();
        final Map<Key, Integer> debits = new HashMap<>();
    }
    private record Key(String channel, long order) {}
    private record Entry(long amount, String status, LocalDate date) {}
    public static class SimulatedCrash extends RuntimeException {
        SimulatedCrash() { super("AFTER_ACCEPT"); }
    }
    Ledger ledger() { return ledger; }
    void restore(Ledger truth) { ledger = truth; }
    void reject(String channel, String reason) { rejected.put(channel, reason); }
    void rejectEvery(String channel, int interval) { rejectEvery.put(channel, interval); }
    void crashAfterAccept(long order) { crashOrder = order; }
    int calls(String channel) { return channelCalls.getOrDefault(channel, 0); }
    int calls(OrderView order) { return ledger.calls.getOrDefault(new Key(order.channelId(), order.orderId()), 0); }
    int debits(OrderView order) { return ledger.debits.getOrDefault(new Key(order.channelId(), order.orderId()), 0); }

    @Override public SubmitResult submit(String channel, long order, long amount) {
        var properties = registry.byId(channel).orElseThrow();
        clock.advance(Duration.ofMillis(properties.latencyMs()));
        Key key = new Key(channel, order);
        ledger.calls.merge(key, 1, Integer::sum);
        int count = channelCalls.merge(channel, 1, Integer::sum);
        String reason = rejected.get(channel);
        if (rejectEvery.containsKey(channel) && count % rejectEvery.get(channel) == 0) reason = "TIMEOUT";
        if (reason != null) return SubmitResult.rejected(reason);
        ledger.debits.merge(key, 1, Integer::sum);
        ledger.entries.put(key, new Entry(amount, "SUCCESS", date()));
        if (Long.valueOf(order).equals(crashOrder)) {
            crashOrder = null;
            throw new SimulatedCrash();
        }
        return SubmitResult.accepted();
    }

    ChannelCallback callback(OrderView order, String status, long sequence) {
        Key key = new Key(order.channelId(), order.orderId());
        Entry entry = ledger.entries.get(key);
        // Once final, bank truth cannot be reversed by a late contradictory callback.
        if (entry != null && sequence == 1 && (status.equals("SUCCESS") || status.equals("FAILED"))) {
            ledger.entries.put(key, new Entry(entry.amount(), status, entry.date()));
        }
        return new ChannelCallback(order.channelId(), order.orderId(), status, sequence, clock.instant());
    }

    @Override public ChannelQueryResult query(String channel, long order) {
        Entry entry = ledger.entries.get(new Key(channel, order));
        return entry == null ? ChannelQueryResult.NOT_FOUND : ChannelQueryResult.valueOf(entry.status());
    }
    LocalDate date() { return clock.instant().atZone(ZoneOffset.UTC).toLocalDate(); }
    void tamper(String channel, UnaryOperator<List<StatementLine>> script) { tampering.put(channel, script); }
    void restoreStatements() { tampering.clear(); }
    @Override public List<StatementLine> statement(String channel, LocalDate date) {
        List<StatementLine> lines = new ArrayList<>();
        ledger.entries.forEach((key, entry) -> {
            if (key.channel().equals(channel) && entry.date().equals(date))
                lines.add(new StatementLine(key.order(), entry.amount(), entry.status()));
        });
        return List.copyOf(tampering.getOrDefault(channel, list -> list).apply(lines));
    }
}
