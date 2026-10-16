package com.example.payplat.payment;

/** Gateway backed by the JVM-static {@link ChannelWorld}. */
public class CrashGateway implements ChannelGateway {
    @Override
    public SubmitResult submit(String channelId, long orderId, long amountCents) {
        ChannelWorld.submit(orderId);
        return SubmitResult.accepted();
    }

    @Override
    public ChannelQueryResult query(String channelId, long orderId) {
        return ChannelWorld.query(orderId);
    }

    // No @Override: compiles before and after ChannelGateway gains statement().
    public java.util.List<StatementLine> statement(String channelId, java.time.LocalDate date) {
        return java.util.List.of();
    }
}
