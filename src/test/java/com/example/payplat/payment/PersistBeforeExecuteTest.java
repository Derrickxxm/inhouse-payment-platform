package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.Tier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;

class PersistBeforeExecuteTest extends PaymentTestBase {
    @Autowired DataSource dataSource;

    @Test
    void taskOrderAndJobAreVisibleToAnotherConnectionWhenTheChannelIsCalled() {
        TaskView v = payments.submit(req("pbe-1", 1000, Tier.FASTEST, 2));
        List<String> problems = new CopyOnWriteArrayList<>();
        gateway.onCall(call -> {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement(
                     "select t.status, o.status, (select count(*) from job j where j.order_id = o.id) "
                         + "from payment_order o join task t on t.id = o.task_id where o.id = ?")) {
                ps.setLong(1, call.orderId());
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) { problems.add("order " + call.orderId() + " not visible"); return; }
                    if (rs.getLong(3) != 1) problems.add("job row not visible for order " + call.orderId());
                }
            } catch (Exception e) {
                problems.add(e.toString());
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()) problems.add("gateway called inside a transaction");
        });
        worker.runOnce();
        assertThat(gateway.calls()).hasSize(2);
        assertThat(problems).isEmpty();
        assertThat(v.orders()).hasSize(2);
    }

    @Test
    void orderStaysCreatedAndJobRunningWhileTheChannelIsBeingCalled() {
        TaskView v = payments.submit(req("pbe-2", 500, Tier.CHEAPEST, 1));
        List<String> seen = new ArrayList<>();
        gateway.onCall(call -> seen.add(orderStatus(call.orderId())
            + "/" + jdbc.queryForObject("select status from job where order_id = ?", String.class, call.orderId())));
        worker.runOnce();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).matches("(CREATED|SUBMITTED)/(RUNNING|PENDING)");
        assertThat(orderStatus(v.orders().get(0).orderId())).isEqualTo("SUBMITTED");
    }

    @Test
    void submitAloneNeverTouchesTheGateway() {
        payments.submit(req("pbe-3", 500, Tier.CHEAPEST, 5));
        assertThat(gateway.calls()).isEmpty();
    }
}
