package com.example.payplat.payment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Test gateway: records every call, rejects configured channels, optional per-call hook. */
public class ScriptedGateway implements ChannelGateway {
    public record Call(String channelId, long orderId, long amountCents) {}

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> rejecting = ConcurrentHashMap.newKeySet();
    private volatile boolean rejectAll;
    private volatile Consumer<Call> hook = c -> {};

    public void reject(String... channels) { rejecting.addAll(List.of(channels)); }
    public void rejectAll() { rejectAll = true; }
    public void onCall(Consumer<Call> h) { hook = h; }
    public List<Call> calls() { synchronized (calls) { return List.copyOf(calls); } }
    public long callsFor(long orderId) { return calls().stream().filter(c -> c.orderId() == orderId).count(); }

    @Override
    public SubmitResult submit(String channelId, long orderId, long amountCents) {
        Call c = new Call(channelId, orderId, amountCents);
        calls.add(c);
        hook.accept(c);
        if (rejectAll || rejecting.contains(channelId)) return SubmitResult.rejected("scripted reject");
        return SubmitResult.accepted();
    }
}
