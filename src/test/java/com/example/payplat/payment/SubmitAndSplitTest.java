package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubmitAndSplitTest extends PaymentTestBase {

    @Test
    void submitPersistsTaskOrdersAndJobsWithoutCallingAnyChannel() {
        TaskView v = payments.submit(req("biz-1", 1000, Tier.CHEAPEST, 2));
        assertThat(v.status()).isEqualTo(TaskStatus.ACCEPTED);
        assertThat(v.bizRequestId()).isEqualTo("biz-1");
        assertThat(v.orders()).hasSize(2);
        assertThat(v.orders()).allSatisfy(o -> {
            assertThat(o.status()).isEqualTo(OrderStatus.CREATED);
            assertThat(o.channelId()).isNull();
            assertThat(o.attempts()).isZero();
        });
        assertThat(count("task")).isEqualTo(1);
        assertThat(count("payment_order")).isEqualTo(2);
        assertThat(count("job")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from job where status = 'PENDING'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, v.taskId())).isEqualTo("ACCEPTED");
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    void evenSplitHasNoRemainder() {
        TaskView v = payments.submit(req("biz-even", 1000, Tier.FASTEST, 4));
        assertThat(v.orders()).extracting(OrderView::amountCents).containsExactly(250L, 250L, 250L, 250L);
    }

    @Test
    void remainderGoesToTheLastOrderAndSumMatches() {
        TaskView v = payments.submit(req("biz-rem", 1000, Tier.FASTEST, 3));
        assertThat(v.orders()).extracting(OrderView::amountCents).containsExactly(333L, 333L, 334L);
        assertThat(v.orders().stream().mapToLong(OrderView::amountCents).sum()).isEqualTo(1000);
    }

    @Test
    void splitAmountsPersistedInTheDatabaseToo() {
        TaskView v = payments.submit(req("biz-db", 1001, Tier.BALANCED, 5));
        List<Long> amounts = jdbc.queryForList(
            "select amount_cents from payment_order where task_id = ? order by id", Long.class, v.taskId());
        assertThat(amounts).containsExactly(200L, 200L, 200L, 200L, 201L);
    }

    @Test
    void singleOrderTaskKeepsFullAmount() {
        TaskView v = payments.submit(req("biz-one", 777, Tier.MOST_RELIABLE, 1));
        assertThat(v.orders()).extracting(OrderView::amountCents).containsExactly(777L);
    }

    @Test
    void getReturnsTheSameViewAsSubmit() {
        TaskView v = payments.submit(req("biz-get", 600, Tier.CHEAPEST, 2));
        assertThat(payments.get(v.taskId())).isEqualTo(v);
    }
}
