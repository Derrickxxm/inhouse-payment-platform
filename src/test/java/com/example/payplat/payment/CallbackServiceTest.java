package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.routing.Tier;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CallbackServiceTest extends Pp3TestBase {

    @Test
    void successCallbackAppliesAndTaskSucceeds() {
        TaskView v = submitted("cb-1", 1);
        OrderView o = v.orders().get(0);
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 1))).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, v.taskId())).isEqualTo("SUCCEEDED");
        assertThat(stateEvents(o.orderId()).get(stateEvents(o.orderId()).size() - 1).get("SOURCE")).isEqualTo("CALLBACK");
    }

    @Test
    void failedCallbackFailsTheTask() {
        TaskView v = submitted("cb-2", 1);
        assertThat(callbacks.onCallback(cb(v.orders().get(0), "FAILED", 1))).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void duplicateCallbackIsDuplicateAndAddsNoSecondAppliedEvent() {
        TaskView v = submitted("cb-3", 1);
        OrderView o = v.orders().get(0);
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 1))).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 1))).isEqualTo(CallbackOutcome.DUPLICATE);
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 2))).isEqualTo(CallbackOutcome.DUPLICATE);
        assertThat(appliedEvents(o.orderId(), "SUCCESS")).isEqualTo(1);
        assertThat(callbackOutcomes(o.orderId())).containsExactly("APPLIED", "DUPLICATE", "DUPLICATE");
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
    }

    @Test
    void outOfOrderProcessingAfterSuccessIsStaleAndFinalStaysSuccess() {
        TaskView v = submitted("cb-4", 1);
        OrderView o = v.orders().get(0);
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 5))).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(callbacks.onCallback(cb(o, "PROCESSING", 3))).isEqualTo(CallbackOutcome.IGNORED_STALE);
        assertThat(callbacks.onCallback(cb(o, "FAILED", 2))).isEqualTo(CallbackOutcome.IGNORED_STALE);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
        assertThat(callbackOutcomes(o.orderId())).containsExactly("APPLIED", "IGNORED_STALE", "IGNORED_STALE");
    }

    @Test
    void lateFailedWithHigherSequenceAfterSuccessIsIgnoredAsIllegal() {
        TaskView v = submitted("cb-5", 1);
        OrderView o = v.orders().get(0);
        callbacks.onCallback(cb(o, "SUCCESS", 5));
        assertThat(callbacks.onCallback(cb(o, "FAILED", 6))).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.SUCCEEDED);
        List<Map<String, Object>> ev = stateEvents(o.orderId());   // the worker's own SUBMITTED event may or may not be present
        assertThat(ev.subList(ev.size() - 2, ev.size())).extracting(e -> e.get("OUTCOME"))
            .containsExactly("APPLIED", "IGNORED_ILLEGAL");
        assertThat(ev.getLast().get("TO_STATUS")).isEqualTo("FAILED");
    }

    @Test
    void wrongChannelIdIsIgnoredAndLoggedWithoutTouchingTheOrder() {
        TaskView v = submitted("cb-6", 1);
        OrderView o = v.orders().get(0);
        int eventsBefore = stateEvents(o.orderId()).size();
        ChannelCallback forged = new ChannelCallback(A, o.orderId(), "SUCCESS", 1, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(o.channelId()).isNotEqualTo(A);
        assertThat(callbacks.onCallback(forged)).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUBMITTED");
        assertThat(stateEvents(o.orderId())).hasSize(eventsBefore);
        assertThat(callbackOutcomes(o.orderId())).containsExactly("IGNORED_ILLEGAL");
        // a forged callback must not burn the sequence number of the real channel
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 1))).isEqualTo(CallbackOutcome.APPLIED);
    }

    @Test
    void processingIsNoopWithNoStateEvent() {
        TaskView v = submitted("cb-7", 1);
        OrderView o = v.orders().get(0);
        int eventsBefore = stateEvents(o.orderId()).size();
        assertThat(callbacks.onCallback(cb(o, "PROCESSING", 1))).isEqualTo(CallbackOutcome.NOOP);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUBMITTED");
        assertThat(stateEvents(o.orderId())).hasSize(eventsBefore);
        assertThat(callbackOutcomes(o.orderId())).containsExactly("NOOP");
        assertThat(task(v.taskId()).status()).isIn(TaskStatus.ACCEPTED, TaskStatus.PROCESSING);
    }

    @Test
    void unknownCallbackMovesToUnknownAndALaterSuccessResolvesIt() {
        TaskView v = submitted("cb-8", 1);
        OrderView o = v.orders().get(0);
        assertThat(callbacks.onCallback(cb(o, "UNKNOWN", 1))).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(orderStatus(o.orderId())).isEqualTo("UNKNOWN");
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(callbacks.onCallback(cb(o, "SUCCESS", 2))).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void garbageInputIsIgnoredWithoutThrowing() {
        TaskView v = submitted("cb-9", 1);
        OrderView o = v.orders().get(0);
        assertThat(callbacks.onCallback(cb(o, "WAT", 1))).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        assertThat(callbacks.onCallback(cb(o, "success", 2))).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        assertThat(callbacks.onCallback(cb(o, null, 3))).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        ChannelCallback ghost = new ChannelCallback(B, 999_999L, "SUCCESS", 1, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(callbacks.onCallback(ghost)).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUBMITTED");
        assertThat(count("callback_log")).isEqualTo(4);
    }

    @Test
    void callbackBeforeSubmissionIsIgnoredBecauseOrderHasNoChannel() {
        TaskView v = payments.submit(req("cb-10", 100, Tier.CHEAPEST, 1));   // worker not run: CREATED, channel null
        OrderView o = v.orders().get(0);
        ChannelCallback early = new ChannelCallback(B, o.orderId(), "SUCCESS", 1, Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(callbacks.onCallback(early)).isEqualTo(CallbackOutcome.IGNORED_ILLEGAL);
        assertThat(orderStatus(o.orderId())).isEqualTo("CREATED");
    }

    @Test
    void callbackLogRecordsRawFields() {
        TaskView v = submitted("cb-11", 1);
        OrderView o = v.orders().get(0);
        callbacks.onCallback(cb(o, "SUCCESS", 7));
        Map<String, Object> row = jdbc.queryForMap("select * from callback_log where order_id = ?", o.orderId());
        assertThat(row.get("CHANNEL_ID")).isEqualTo(o.channelId());
        assertThat(row.get("STATUS")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("SEQUENCE")).longValue()).isEqualTo(7);
        assertThat(row.get("OUTCOME")).isEqualTo("APPLIED");
        assertThat(row.get("RECEIVED_AT")).isNotNull();
        assertThat(row.get("SENT_AT")).isNotNull();
    }

    @Test
    void splitTaskIsNotFinalizedWhenOneSliceFailsAndNeverSucceeded() {
        TaskView v = submitted("cb-12", 3);
        List<OrderView> o = v.orders();
        callbacks.onCallback(cb(o.get(0), "SUCCESS", 1));
        callbacks.onCallback(cb(o.get(1), "SUCCESS", 1));
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.PROCESSING);
        callbacks.onCallback(cb(o.get(2), "FAILED", 1));
        TaskView t = task(v.taskId());
        assertThat(t.status()).isEqualTo(TaskStatus.NOT_FINALIZED);
        assertThat(t.reason()).isEqualTo("partial: 2/3 succeeded");
    }

    @Test
    void splitTaskSucceedsOnlyAfterEveryOrderSucceeds() {
        TaskView v = submitted("cb-13", 3);
        List<OrderView> o = v.orders();
        for (int i = 0; i < 3; i++) {
            assertThat(task(v.taskId()).status()).isNotEqualTo(TaskStatus.SUCCEEDED);
            callbacks.onCallback(cb(o.get(i), "SUCCESS", 1));
        }
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void sequencesAreTrackedPerOrder() {
        TaskView v = submitted("cb-14", 2);
        List<OrderView> o = v.orders();
        callbacks.onCallback(cb(o.get(0), "PROCESSING", 10));
        assertThat(callbacks.onCallback(cb(o.get(1), "SUCCESS", 1))).isEqualTo(CallbackOutcome.APPLIED);
    }
}
