package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payplat.routing.Tier;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderStateMachineTest extends Pp3TestBase {

    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL = Map.of(
        OrderStatus.CREATED, Set.of(OrderStatus.SUBMITTED),
        OrderStatus.SUBMITTED, Set.of(OrderStatus.SUCCESS, OrderStatus.FAILED, OrderStatus.UNKNOWN),
        OrderStatus.UNKNOWN, Set.of(OrderStatus.SUCCESS, OrderStatus.FAILED),
        OrderStatus.SUCCESS, Set.of(),
        OrderStatus.FAILED, Set.of());

    private long freshOrderIn(OrderStatus from, int n) {
        TaskView v = payments.submit(req("m-" + from + "-" + n, 100, Tier.CHEAPEST, 1));
        long id = v.orders().get(0).orderId();
        force(id, from);
        return id;
    }

    @Test
    void fullTransitionMatrix() {
        int n = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                long id = freshOrderIn(from, n++);
                ApplyResult expected = from == to ? ApplyResult.DUPLICATE
                    : LEGAL.get(from).contains(to) ? ApplyResult.APPLIED : ApplyResult.IGNORED_ILLEGAL;
                ApplyResult actual = orderStates.apply(id, to, "TEST");
                assertThat(actual).as("%s -> %s", from, to).isEqualTo(expected);
                OrderStatus expectedState = expected == ApplyResult.APPLIED ? to : from;
                assertThat(orderStatus(id)).as("state after %s -> %s", from, to).isEqualTo(expectedState.name());
            }
        }
    }

    @Test
    void terminalStatesAreImmutable() {
        long id = freshOrderIn(OrderStatus.SUBMITTED, 0);
        assertThat(orderStates.apply(id, OrderStatus.SUCCESS)).isEqualTo(ApplyResult.APPLIED);
        for (OrderStatus other : OrderStatus.values()) {
            if (other == OrderStatus.SUCCESS) continue;
            assertThat(orderStates.apply(id, other)).as("SUCCESS -> %s", other).isEqualTo(ApplyResult.IGNORED_ILLEGAL);
        }
        assertThat(orderStatus(id)).isEqualTo("SUCCESS");
    }

    @Test
    void legalPathThroughUnknownToSuccess() {
        TaskView v = payments.submit(req("path-1", 100, Tier.CHEAPEST, 1));
        long id = v.orders().get(0).orderId();
        assertThat(orderStates.apply(id, OrderStatus.SUBMITTED)).isEqualTo(ApplyResult.APPLIED);
        assertThat(orderStates.apply(id, OrderStatus.UNKNOWN)).isEqualTo(ApplyResult.APPLIED);
        assertThat(orderStates.apply(id, OrderStatus.SUCCESS)).isEqualTo(ApplyResult.APPLIED);
        assertThat(orderStatus(id)).isEqualTo("SUCCESS");
    }

    @Test
    void createdCannotSkipToAnythingButSubmitted() {
        long id = freshOrderIn(OrderStatus.CREATED, 0);
        assertThat(orderStates.apply(id, OrderStatus.SUCCESS)).isEqualTo(ApplyResult.IGNORED_ILLEGAL);
        assertThat(orderStates.apply(id, OrderStatus.FAILED)).isEqualTo(ApplyResult.IGNORED_ILLEGAL);
        assertThat(orderStates.apply(id, OrderStatus.UNKNOWN)).isEqualTo(ApplyResult.IGNORED_ILLEGAL);
        assertThat(orderStatus(id)).isEqualTo("CREATED");
    }

    @Test
    void everyOutcomeIsRecordedInStateEventWithFromToOutcomeSourceAndClockTime() {
        long id = freshOrderIn(OrderStatus.CREATED, 0);
        orderStates.apply(id, OrderStatus.SUBMITTED, "TEST");   // APPLIED
        orderStates.apply(id, OrderStatus.SUBMITTED, "TEST");   // DUPLICATE
        orderStates.apply(id, OrderStatus.SUCCESS);             // APPLIED, default source
        orderStates.apply(id, OrderStatus.FAILED, "TEST");      // IGNORED_ILLEGAL
        List<Map<String, Object>> ev = stateEvents(id);
        assertThat(ev).hasSize(4);
        assertThat(ev).extracting(e -> e.get("OUTCOME"))
            .containsExactly("APPLIED", "DUPLICATE", "APPLIED", "IGNORED_ILLEGAL");
        assertThat(ev).extracting(e -> e.get("FROM_STATUS"))
            .containsExactly("CREATED", "SUBMITTED", "SUBMITTED", "SUCCESS");
        assertThat(ev).extracting(e -> e.get("TO_STATUS"))
            .containsExactly("SUBMITTED", "SUBMITTED", "SUCCESS", "FAILED");
        assertThat(ev).extracting(e -> e.get("SOURCE")).containsExactly("TEST", "TEST", "DIRECT", "TEST");
        assertThat(ev).allSatisfy(e ->
            assertThat(((Timestamp) e.get("AT")).toInstant()).isEqualTo(clock.instant()));
    }

    @Test
    void appliedTouchesUpdatedAtButDuplicateAndIllegalDoNot() {
        long id = freshOrderIn(OrderStatus.CREATED, 0);
        clock.advance(Duration.ofSeconds(10));
        orderStates.apply(id, OrderStatus.SUBMITTED);
        Timestamp afterApply = jdbc.queryForObject("select updated_at from payment_order where id = ?", Timestamp.class, id);
        assertThat(afterApply.toInstant()).isEqualTo(clock.instant());
        clock.advance(Duration.ofSeconds(10));
        orderStates.apply(id, OrderStatus.SUBMITTED);   // duplicate
        orderStates.apply(id, OrderStatus.CREATED);     // illegal
        Timestamp later = jdbc.queryForObject("select updated_at from payment_order where id = ?", Timestamp.class, id);
        assertThat(later).isEqualTo(afterApply);
    }

    @Test
    void illegalAndDuplicateNeverThrowButUnknownOrderDoes() {
        long id = freshOrderIn(OrderStatus.SUCCESS, 0);
        assertThatCode(() -> {
            orderStates.apply(id, OrderStatus.FAILED);
            orderStates.apply(id, OrderStatus.SUCCESS);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> orderStates.apply(999_999L, OrderStatus.SUBMITTED))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void concurrentConflictingTerminalAppliesProduceExactlyOneWinner() throws Exception {
        for (int round = 0; round < 15; round++) {
            long id = freshOrderIn(OrderStatus.SUBMITTED, round);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var a = pool.submit(() -> { barrier.await(); return orderStates.apply(id, OrderStatus.SUCCESS, "A"); });
                var b = pool.submit(() -> { barrier.await(); return orderStates.apply(id, OrderStatus.FAILED, "B"); });
                List<ApplyResult> r = List.of(a.get(), b.get());
                assertThat(r).containsExactlyInAnyOrder(ApplyResult.APPLIED, ApplyResult.IGNORED_ILLEGAL);
                assertThat(appliedEvents(id, "SUCCESS") + appliedEvents(id, "FAILED")).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
