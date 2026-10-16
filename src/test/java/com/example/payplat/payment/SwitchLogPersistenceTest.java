package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.SwitchLog;
import com.example.payplat.routing.Tier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SwitchLogPersistenceTest extends PaymentTestBase {
    @Autowired SwitchLog switchLog;

    @Test
    void plainRecordIsStoredInTheTableAndEntriesApiStillWorks() {
        switchLog.record(C, A, "SUBMIT_FAILED");
        switchLog.record(A, W, "RATE_LIMITED");
        assertThat(count("channel_switch_log")).isEqualTo(2);
        assertThat(switchLog.entries()).containsExactly(
            new SwitchLog.Entry(C, A, "SUBMIT_FAILED"),
            new SwitchLog.Entry(A, W, "RATE_LIMITED"));
        assertThat(jdbc.queryForObject(
            "select from_channel || '>' || to_channel || ':' || reason from channel_switch_log order by id limit 1", String.class))
            .isEqualTo("FakeBankC>FakeBankA:SUBMIT_FAILED");
    }

    @Test
    void plainRecordHasNullTaskAndOrder() {
        switchLog.record(C, A, "SUBMIT_FAILED");
        assertThat(switchLog.rows()).containsExactly(new SwitchLog.Row(null, null, C, A, "SUBMIT_FAILED"));
    }

    @Test
    void entriesIsAnImmutableCopy() {
        switchLog.record(C, A, "X");
        var snapshot = switchLog.entries();
        switchLog.record(A, W, "Y");
        assertThat(snapshot).hasSize(1);
        assertThat(switchLog.entries()).hasSize(2);
    }

    @Test
    void workerFailoverPersistsSwitchRowWithTaskAndOrderIds() {
        gateway.reject(C);
        TaskView v = payments.submit(req("sw-1", 400, Tier.FASTEST, 1));
        worker.runOnce();
        long orderId = v.orders().get(0).orderId();
        assertThat(switchLog.rows()).containsExactly(new SwitchLog.Row(v.taskId(), orderId, C, A, "SUBMIT_FAILED"));
        assertThat(jdbc.queryForObject(
            "select task_id from channel_switch_log where order_id = ?", Long.class, orderId)).isEqualTo(v.taskId());
    }

    @Test
    void multipleSwitchesForOneOrderAreLoggedInOrder() {
        gateway.reject(C, A);
        TaskView v = payments.submit(req("sw-2", 400, Tier.FASTEST, 1));
        worker.runOnce();
        long orderId = v.orders().get(0).orderId();
        assertThat(switchLog.rows()).containsExactly(
            new SwitchLog.Row(v.taskId(), orderId, C, A, "SUBMIT_FAILED"),
            new SwitchLog.Row(v.taskId(), orderId, A, W, "SUBMIT_FAILED"));
    }

    @Test
    void noSwitchRowsWhenFirstChoiceAccepts() {
        payments.submit(req("sw-3", 400, Tier.FASTEST, 2));
        worker.runOnce();
        assertThat(count("channel_switch_log")).isZero();
    }
}
