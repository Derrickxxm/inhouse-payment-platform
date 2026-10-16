package com.example.payplat.payment;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Test gateway: records every call, rejects configured channels, optional per-call hook. */
public class ScriptedGateway implements ChannelGateway {
    public record Call(String channelId, long orderId, long amountCents) {}

    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> rejecting = ConcurrentHashMap.newKeySet();
    private volatile boolean rejectAll;
    public record Query(String channelId, long orderId) {}

    private final List<Query> queries = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, ChannelQueryResult> answers = new ConcurrentHashMap<>();
    private volatile Consumer<Call> hook = c -> {};

    public void reject(String... channels) { rejecting.addAll(List.of(channels)); }
    public void rejectAll() { rejectAll = true; }
    public void onCall(Consumer<Call> h) { hook = h; }
    public List<Call> calls() { synchronized (calls) { return List.copyOf(calls); } }
    public long callsFor(long orderId) { return calls().stream().filter(c -> c.orderId() == orderId).count(); }

    /** Scripts what query() answers for an order; unscripted orders answer PROCESSING. */
    public void answer(long orderId, ChannelQueryResult r) { answers.put(orderId, r); }
    public List<Query> queries() { synchronized (queries) { return List.copyOf(queries); } }
    public long queriesFor(long orderId) { return queries().stream().filter(q -> q.orderId() == orderId).count(); }

    public record StatementCall(String channelId, LocalDate date) {}

    private final List<StatementCall> statementCalls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<StatementLine>> statements = new ConcurrentHashMap<>();
    private volatile boolean failStatements;

    /** Scripts what statement() returns for a channel and date; unscripted answers are empty. */
    public void statement(String channelId, LocalDate date, List<StatementLine> lines) { statements.put(channelId + "|" + date, lines); }
    public void failStatements() { failStatements = true; }
    public void healStatements() { failStatements = false; }
    public List<StatementCall> statementCalls() { synchronized (statementCalls) { return List.copyOf(statementCalls); } }

    // No @Override: compiles before and after ChannelGateway gains statement().
    public List<StatementLine> statement(String channelId, LocalDate date) {
        statementCalls.add(new StatementCall(channelId, date));
        if (failStatements) throw new IllegalStateException("statement unavailable");
        return statements.getOrDefault(channelId + "|" + date, List.of());
    }

    @Override
    public ChannelQueryResult query(String channelId, long orderId) {
        queries.add(new Query(channelId, orderId));
        return answers.getOrDefault(orderId, ChannelQueryResult.PROCESSING);
    }

    @Override
    public SubmitResult submit(String channelId, long orderId, long amountCents) {
        Call c = new Call(channelId, orderId, amountCents);
        calls.add(c);
        hook.accept(c);
        if (rejectAll || rejecting.contains(channelId)) return SubmitResult.rejected("scripted reject");
        return SubmitResult.accepted();
    }
}
