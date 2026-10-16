package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payplat.routing.Tier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonitoringTest extends Pp5TestBase {

    private void durations(long... ms) {
        AtomicInteger n = new AtomicInteger();
        gateway.onCall(c -> clock.advance(Duration.ofMillis(ms[n.getAndIncrement()])));
    }

    @Test
    void noCallsMeansEmptySnapshotAndEmptyJson() {
        assertThat(monitoring.snapshot()).isEmpty();
        assertThat(monitoring.toJson()).isEqualTo("{}");
    }

    @Test
    void everySubmitAttemptIsLoggedWithChannelOrderOutcomeAndDuration() {
        durations(37);
        TaskView v = payments.submit(req("mon-1", 100, Tier.CHEAPEST, 1));
        worker.runOnce();
        var rows = jdbc.queryForList("select * from gateway_call_log");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("CHANNEL_ID", B).containsEntry("ORDER_ID", v.orders().get(0).orderId())
            .containsEntry("ACCEPTED", true).containsEntry("DURATION_MS", 37L);
        assertThat(rows.get(0).get("REASON")).isNull();
        assertThat(rows.get(0).get("AT")).isNotNull();
    }

    @Test
    void attemptsSuccessesAndFailureReasonsPerChannelIncludingFailover() {
        gateway.reject(C);
        payments.submit(req("mon-2a", 400, Tier.FASTEST, 1));
        payments.submit(req("mon-2b", 400, Tier.FASTEST, 1));
        worker.runOnce();
        Map<String, ChannelStats> s = monitoring.snapshot();
        assertThat(s.keySet()).containsExactly(A, C);
        ChannelStats c = s.get(C);
        assertThat(c.attempts()).isEqualTo(2);
        assertThat(c.successes()).isZero();
        assertThat(c.successRate()).isEqualTo(0.0);
        assertThat(c.failureReasons()).isEqualTo(Map.of("scripted reject", 2L));
        ChannelStats a = s.get(A);
        assertThat(a.attempts()).isEqualTo(2);
        assertThat(a.successes()).isEqualTo(2);
        assertThat(a.successRate()).isEqualTo(1.0);
        assertThat(a.failureReasons()).isEmpty();
    }

    @Test
    void successRateIsSuccessesOverAttempts() {
        gateway.reject(B);
        // 3 orders (the breaker opens after 3 straight failures); B rejects all, W (next CHEAPEST) accepts: B 0/3, W 3/3
        payments.submit(req("mon-3", 300, Tier.CHEAPEST, 3));
        worker.runOnce();
        assertThat(monitoring.snapshot().get(B).successRate()).isEqualTo(0.0);
        assertThat(monitoring.snapshot().get(W).successRate()).isEqualTo(1.0);
        assertThat(monitoring.snapshot().get(B).attempts()).isEqualTo(3);
    }

    @Test
    void p50AndP95UseNearestRankOverTenSamples() {
        durations(10, 20, 30, 40, 50, 60, 70, 80, 90, 100);
        for (int i = 0; i < 2; i++) payments.submit(req("mon-4-" + i, 500, Tier.CHEAPEST, 5)); // splitInto max is 5 (PP-2)
        worker.runOnce();
        ChannelStats b = monitoring.snapshot().get(B);
        assertThat(b.attempts()).isEqualTo(10);
        assertThat(b.p50Ms()).isEqualTo(50);
        assertThat(b.p95Ms()).isEqualTo(100);
    }

    @Test
    void p50AndP95UseNearestRankOverTwentySamplesRegardlessOfCallOrder() {
        durations(20, 1, 19, 2, 18, 3, 17, 4, 16, 5, 15, 6, 14, 7, 13, 8, 12, 9, 11, 10);
        for (int i = 0; i < 4; i++) payments.submit(req("mon-5-" + i, 500, Tier.CHEAPEST, 5)); // splitInto max is 5 (PP-2)
        worker.runOnce();
        ChannelStats b = monitoring.snapshot().get(B);
        assertThat(b.p50Ms()).isEqualTo(10);
        assertThat(b.p95Ms()).isEqualTo(19);
    }

    @Test
    void aSingleSampleIsBothPercentiles() {
        durations(250);
        payments.submit(req("mon-6", 100, Tier.CHEAPEST, 1));
        worker.runOnce();
        ChannelStats b = monitoring.snapshot().get(B);
        assertThat(b.p50Ms()).isEqualTo(250);
        assertThat(b.p95Ms()).isEqualTo(250);
    }

    @Test
    void aGatewayExceptionIsLoggedAsAFailureAndRethrown() {
        gateway.onCall(c -> { throw new IllegalStateException("boom"); });
        payments.submit(req("mon-7", 100, Tier.CHEAPEST, 1));
        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        ChannelStats b = monitoring.snapshot().get(B);
        assertThat(b.attempts()).isEqualTo(1);
        assertThat(b.successes()).isZero();
        assertThat(b.failureReasons()).isEqualTo(Map.of("EXCEPTION:IllegalStateException", 1L));
    }

    @Test
    void channelsWithoutAttemptsDoNotAppear() {
        payments.submit(req("mon-8", 100, Tier.CHEAPEST, 1));
        worker.runOnce();
        assertThat(monitoring.snapshot().keySet()).containsExactly(B);
    }

    @Test
    void jsonHasTheDocumentedShape() throws Exception {
        gateway.reject(C);
        durations(30, 70);
        payments.submit(req("mon-9", 400, Tier.FASTEST, 1));
        worker.runOnce();
        JsonNode root = new ObjectMapper().readTree(monitoring.toJson());
        assertThat(root.fieldNames()).toIterable().containsExactly(A, C);
        JsonNode c = root.get(C);
        assertThat(c.get("attempts").asLong()).isEqualTo(1);
        assertThat(c.get("successes").asLong()).isZero();
        assertThat(c.get("successRate").asDouble()).isEqualTo(0.0);
        assertThat(c.get("p50Ms").asLong()).isEqualTo(30);
        assertThat(c.get("p95Ms").asLong()).isEqualTo(30);
        assertThat(c.get("failureReasons").get("scripted reject").asLong()).isEqualTo(1);
        JsonNode a = root.get(A);
        assertThat(a.get("successRate").asDouble()).isEqualTo(1.0);
        assertThat(a.get("p50Ms").asLong()).isEqualTo(70);
        assertThat(a.get("failureReasons").size()).isZero();
    }

    @Test
    void rateLimitedSkipsMakeNoLogRow() {
        // FakeBankA allows 2 calls/sec: the third FASTEST order skips A and C is rejected -> only real calls are logged
        gateway.reject(C);
        payments.submit(req("mon-10", 300, Tier.FASTEST, 3));
        worker.runOnce();
        long calls = gateway.calls().size();
        assertThat(count("gateway_call_log")).isEqualTo(calls);
        assertThat(List.copyOf(monitoring.snapshot().values()).stream().mapToLong(ChannelStats::attempts).sum()).isEqualTo(calls);
    }
}
