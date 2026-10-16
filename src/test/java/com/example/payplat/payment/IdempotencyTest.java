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

class IdempotencyTest extends PaymentTestBase {

    @Test
    void sameBizRequestIdReturnsExistingTaskAndCreatesNothing() {
        TaskView first = payments.submit(req("dup-1", 900, Tier.CHEAPEST, 3));
        TaskView second = payments.submit(req("dup-1", 900, Tier.CHEAPEST, 3));
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.orders()).isEqualTo(first.orders());
        assertThat(count("task")).isEqualTo(1);
        assertThat(count("payment_order")).isEqualTo(3);
        assertThat(count("job")).isEqualTo(3);
    }

    @Test
    void repeatedIdWithDifferentFieldsStillReturnsOriginal() {
        TaskView first = payments.submit(req("dup-2", 900, Tier.CHEAPEST, 3));
        TaskView second = payments.submit(req("dup-2", 50, Tier.FASTEST, 1));
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.orders()).hasSize(3);
        assertThat(second.orders().stream().mapToLong(OrderView::amountCents).sum()).isEqualTo(900);
        assertThat(count("task")).isEqualTo(1);
    }

    @Test
    void repeatedSubmitReflectsCurrentTaskStatus() {
        TaskView v = payments.submit(req("dup-3", 100, Tier.CHEAPEST, 1));
        drive(v.orders().get(0).orderId(), OrderStatus.SUCCESS);
        tasks.refresh(v.taskId());
        assertThat(payments.submit(req("dup-3", 100, Tier.CHEAPEST, 1)).status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void differentIdsCreateDifferentTasks() {
        TaskView a = payments.submit(req("x-1", 100, Tier.CHEAPEST, 1));
        TaskView b = payments.submit(req("x-2", 100, Tier.CHEAPEST, 1));
        assertThat(a.taskId()).isNotEqualTo(b.taskId());
        assertThat(count("task")).isEqualTo(2);
    }

    @Test
    void concurrentSubmitsOfTheSameIdProduceExactlyOneTask() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                String id = "race-" + round;
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<TaskView>> fs = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    fs.add(pool.submit(() -> {
                        barrier.await();
                        return payments.submit(req(id, 1000, Tier.BALANCED, 3));
                    }));
                }
                TaskView v1 = fs.get(0).get();
                TaskView v2 = fs.get(1).get();
                assertThat(v1.taskId()).isEqualTo(v2.taskId());
                assertThat(v1.orders()).hasSize(3);
                assertThat(v2.orders()).hasSize(3);
                assertThat(jdbc.queryForObject("select count(*) from task where biz_request_id = ?", Long.class, id)).isEqualTo(1);
            }
            assertThat(count("task")).isEqualTo(25);
            assertThat(count("payment_order")).isEqualTo(75);
            assertThat(count("job")).isEqualTo(75);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidRequestCreatesNothing() {
        try {
            payments.submit(req("bad", 100, Tier.CHEAPEST, 9));
        } catch (IllegalArgumentException expected) {
            // thrown by the DebitRequest constructor already
        }
        assertThat(count("task")).isZero();
    }
}
