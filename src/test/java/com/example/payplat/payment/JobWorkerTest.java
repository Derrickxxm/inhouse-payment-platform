package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** FASTEST ranking with test-channels.properties is [C, A, W, B]; CHEAPEST is [B, W, A, C]. */
class JobWorkerTest extends PaymentTestBase {

    @Test
    void processesPendingJobsAndSubmitsOnTheTierTopChannel() {
        TaskView v = payments.submit(req("w-1", 1000, Tier.CHEAPEST, 2));
        assertThat(worker.runOnce()).isEqualTo(2);
        TaskView after = payments.get(v.taskId());
        assertThat(after.orders()).allSatisfy(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(o.channelId()).isEqualTo(B);
            assertThat(o.attempts()).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("select count(*) from job where status = 'DONE'", Long.class)).isEqualTo(2);
    }

    @Test
    void noPendingJobsMeansNothingHappens() {
        assertThat(worker.runOnce()).isZero();
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    void secondRunNeverResubmitsSubmittedOrders() {
        TaskView v = payments.submit(req("w-2", 1000, Tier.CHEAPEST, 3));
        worker.runOnce();
        assertThat(worker.runOnce()).isZero();
        assertThat(gateway.calls()).hasSize(3);
        for (OrderView o : v.orders()) assertThat(gateway.callsFor(o.orderId())).isEqualTo(1);
    }

    @Test
    void rejectedChannelFailsOverToTheNextAndCountsAttempts() {
        gateway.reject(C);
        TaskView v = payments.submit(req("w-3", 400, Tier.FASTEST, 1));
        worker.runOnce();
        OrderView o = payments.get(v.taskId()).orders().get(0);
        assertThat(o.status()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(o.channelId()).isEqualTo(A);
        assertThat(o.attempts()).isEqualTo(2);
        assertThat(gateway.calls()).extracting(ScriptedGateway.Call::channelId).containsExactly(C, A);
    }

    @Test
    void noChannelIsCalledTwiceForTheSameOrderInOneRun() {
        gateway.rejectAll();
        TaskView v = payments.submit(req("w-4", 400, Tier.FASTEST, 1));
        worker.runOnce();
        List<String> channels = gateway.calls().stream().map(ScriptedGateway.Call::channelId).toList();
        assertThat(channels).doesNotHaveDuplicates();
        assertThat(channels).hasSizeLessThanOrEqualTo(4);
        assertThat(v.orders()).hasSize(1);
    }

    @Test
    void allChannelsRejectingFailsTheOrderWithAReason() {
        gateway.rejectAll();
        TaskView v = payments.submit(req("w-5", 400, Tier.FASTEST, 1));
        worker.runOnce();
        OrderView o = payments.get(v.taskId()).orders().get(0);
        assertThat(o.status()).isEqualTo(OrderStatus.FAILED);
        assertThat(o.reason()).isEqualTo("NO_ELIGIBLE_CHANNEL");
        assertThat(jdbc.queryForObject("select status from job where order_id = ?", String.class, o.orderId())).isEqualTo("DONE");
        assertThat(worker.runOnce()).isZero();
    }

    @Test
    void concurrentWorkersNeverSubmitTheSameOrderTwice() throws Exception {
        TaskView v = payments.submit(req("w-6", 1000, Tier.CHEAPEST, 5));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { barrier.await(); return worker.runOnce(); }));
            int claimed = fs.get(0).get() + fs.get(1).get();
            assertThat(claimed).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        for (OrderView o : v.orders()) assertThat(gateway.callsFor(o.orderId())).isEqualTo(1);
        assertThat(gateway.calls()).hasSize(5);
    }
}
