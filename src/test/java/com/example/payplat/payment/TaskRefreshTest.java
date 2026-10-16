package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRefreshTest extends PaymentTestBase {

    private TaskView taskWith(String id, int n, OrderStatus... statuses) {
        TaskView v = payments.submit(req(id, 1000, Tier.BALANCED, n));
        for (int i = 0; i < statuses.length; i++) drive(v.orders().get(i).orderId(), statuses[i]);
        return v;
    }

    @Test
    void allSuccessIsSucceeded() {
        TaskView v = taskWith("r-1", 3, OrderStatus.SUCCESS, OrderStatus.SUCCESS, OrderStatus.SUCCESS);
        TaskView r = tasks.refresh(v.taskId());
        assertThat(r.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(r.reason()).isNull();
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, v.taskId())).isEqualTo("SUCCEEDED");
    }

    @Test
    void allFailedIsFailed() {
        TaskView v = taskWith("r-2", 2, OrderStatus.FAILED, OrderStatus.FAILED);
        assertThat(tasks.refresh(v.taskId()).status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void successPlusFailedWithNothingPendingIsNotFinalizedWithPartialReason() {
        TaskView v = taskWith("r-3", 3, OrderStatus.SUCCESS, OrderStatus.SUCCESS, OrderStatus.FAILED);
        TaskView r = tasks.refresh(v.taskId());
        assertThat(r.status()).isEqualTo(TaskStatus.NOT_FINALIZED);
        assertThat(r.reason()).isEqualTo("partial: 2/3 succeeded");
        assertThat(jdbc.queryForObject("select reason from task where id = ?", String.class, v.taskId()))
            .isEqualTo("partial: 2/3 succeeded");
    }

    @Test
    void pendingOrdersKeepTheTaskProcessing() {
        TaskView v = taskWith("r-4", 2, OrderStatus.SUCCESS, OrderStatus.SUBMITTED);
        assertThat(tasks.refresh(v.taskId()).status()).isEqualTo(TaskStatus.PROCESSING);
    }

    @Test
    void freshTaskRefreshesToProcessing() {
        TaskView v = payments.submit(req("r-5", 100, Tier.CHEAPEST, 2));
        assertThat(tasks.refresh(v.taskId()).status()).isEqualTo(TaskStatus.PROCESSING);
    }

    @Test
    void neverReportsPartialAsSuccessAcrossAllStatusCombinations() {
        OrderStatus[] all = OrderStatus.values();
        TaskView v = payments.submit(req("r-6", 1000, Tier.BALANCED, 3));
        List<OrderView> orders = v.orders();
        for (OrderStatus a : all) for (OrderStatus b : all) for (OrderStatus c : all) {
            OrderStatus[] s = {a, b, c};
            for (int i = 0; i < 3; i++) force(orders.get(i).orderId(), s[i]);
            TaskView r = tasks.refresh(v.taskId());
            long ok = java.util.Arrays.stream(s).filter(x -> x == OrderStatus.SUCCESS).count();
            long bad = java.util.Arrays.stream(s).filter(x -> x == OrderStatus.FAILED).count();
            TaskStatus expected;
            if (ok == 3) expected = TaskStatus.SUCCEEDED;
            else if (bad == 3) expected = TaskStatus.FAILED;
            else if (ok > 0 && bad > 0 && ok + bad == 3) expected = TaskStatus.NOT_FINALIZED;
            else expected = TaskStatus.PROCESSING;
            assertThat(r.status()).as("%s,%s,%s", a, b, c).isEqualTo(expected);
            if (r.status() == TaskStatus.SUCCEEDED) assertThat(ok).isEqualTo(3);
            if (expected == TaskStatus.NOT_FINALIZED) assertThat(r.reason()).isEqualTo("partial: " + ok + "/3 succeeded");
        }
    }

    @Test
    void endToEndSplitWithOneRejectedSliceNeverSucceeds() {
        TaskView v = payments.submit(req("r-7", 900, Tier.CHEAPEST, 3));
        worker.runOnce();
        List<OrderView> o = payments.get(v.taskId()).orders();
        drive(o.get(0).orderId(), OrderStatus.SUCCESS);
        drive(o.get(1).orderId(), OrderStatus.SUCCESS);
        drive(o.get(2).orderId(), OrderStatus.FAILED);
        TaskView r = tasks.refresh(v.taskId());
        assertThat(r.status()).isNotEqualTo(TaskStatus.SUCCEEDED).isEqualTo(TaskStatus.NOT_FINALIZED);
    }
}
