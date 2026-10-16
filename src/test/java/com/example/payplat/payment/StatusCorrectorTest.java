package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusCorrectorTest extends Pp3TestBase {

    private static final Duration PAST_WINDOW = Duration.ofSeconds(61);

    @Test
    void submittedOrderBecomesSuccessFromChannelTruthAndTaskSucceeds() {
        TaskView v = submitted("sc-1", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.SUCCESS);
        clock.advance(PAST_WINDOW);
        assertThat(corrector.runOnce()).isEqualTo(1);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(stateEvents(o.orderId()).getLast().get("SOURCE")).isEqualTo("QUERY");
        assertThat(gateway.queries()).containsExactly(new ScriptedGateway.Query(o.channelId(), o.orderId()));
    }

    @Test
    void submittedOrderBecomesFailedFromChannelTruth() {
        TaskView v = submitted("sc-2", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.FAILED);
        clock.advance(PAST_WINDOW);
        corrector.runOnce();
        assertThat(orderStatus(o.orderId())).isEqualTo("FAILED");
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void notFoundOnSubmittedBecomesUnknownAndIsNeverResubmitted() {
        TaskView v = submitted("sc-3", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.NOT_FOUND);
        clock.advance(PAST_WINDOW);
        corrector.runOnce();
        assertThat(orderStatus(o.orderId())).isEqualTo("UNKNOWN");
        assertThat(gateway.callsFor(o.orderId())).isEqualTo(1);   // the original submit only
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.PROCESSING);
    }

    @Test
    void unknownOrderIsResolvedByLaterChannelTruth() {
        TaskView v = submitted("sc-4", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.NOT_FOUND);
        clock.advance(PAST_WINDOW);
        corrector.runOnce();
        gateway.answer(o.orderId(), ChannelQueryResult.SUCCESS);
        clock.advance(PAST_WINDOW);
        assertThat(corrector.runOnce()).isEqualTo(1);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
        assertThat(task(v.taskId()).status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void unknownOrderStaysUnknownOnNotFoundWithoutNewStateEvents() {
        TaskView v = submitted("sc-5", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.NOT_FOUND);
        clock.advance(PAST_WINDOW);
        corrector.runOnce();                       // SUBMITTED -> UNKNOWN
        int events = stateEvents(o.orderId()).size();
        clock.advance(PAST_WINDOW);
        assertThat(corrector.runOnce()).isEqualTo(1);   // queried again, still NOT_FOUND
        assertThat(orderStatus(o.orderId())).isEqualTo("UNKNOWN");
        assertThat(stateEvents(o.orderId())).hasSize(events);
        assertThat(gateway.callsFor(o.orderId())).isEqualTo(1);
    }

    @Test
    void processingAnswerLeavesTheOrderAlone() {
        TaskView v = submitted("sc-6", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.PROCESSING);
        clock.advance(PAST_WINDOW);
        assertThat(corrector.runOnce()).isEqualTo(1);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUBMITTED");
    }

    @Test
    void respectsTheDefaultSixtySecondWindow() {
        TaskView v = submitted("sc-7", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.SUCCESS);
        clock.advance(Duration.ofSeconds(59));
        assertThat(corrector.runOnce()).isZero();
        assertThat(gateway.queries()).isEmpty();
        assertThat(orderStatus(o.orderId())).isEqualTo("SUBMITTED");
        clock.advance(Duration.ofSeconds(2));
        assertThat(corrector.runOnce()).isEqualTo(1);
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
    }

    @Test
    void correctionRestartsTheWindow() {
        TaskView v = submitted("sc-8", 1);
        OrderView o = v.orders().get(0);
        gateway.answer(o.orderId(), ChannelQueryResult.NOT_FOUND);
        clock.advance(PAST_WINDOW);
        corrector.runOnce();                                // -> UNKNOWN, updated_at = now
        assertThat(corrector.runOnce()).isZero();           // same instant: inside the new window
        assertThat(gateway.queriesFor(o.orderId())).isEqualTo(1);
    }

    @Test
    void terminalAndCreatedOrdersAreNeverQueried() {
        TaskView done = submitted("sc-9", 2);
        callbacks.onCallback(cb(done.orders().get(0), "SUCCESS", 1));
        callbacks.onCallback(cb(done.orders().get(1), "FAILED", 1));
        TaskView fresh = payments.submit(req("sc-9b", 100, com.example.payplat.routing.Tier.CHEAPEST, 1));   // CREATED
        clock.advance(Duration.ofHours(1));
        assertThat(corrector.runOnce()).isZero();
        assertThat(gateway.queries()).isEmpty();
        assertThat(orderStatus(fresh.orders().get(0).orderId())).isEqualTo("CREATED");
    }

    @Test
    void mixedTaskEndsNotFinalizedAfterCorrection() {
        TaskView v = submitted("sc-10", 2);
        List<OrderView> o = v.orders();
        gateway.answer(o.get(0).orderId(), ChannelQueryResult.SUCCESS);
        gateway.answer(o.get(1).orderId(), ChannelQueryResult.FAILED);
        clock.advance(PAST_WINDOW);
        assertThat(corrector.runOnce()).isEqualTo(2);
        TaskView t = task(v.taskId());
        assertThat(t.status()).isEqualTo(TaskStatus.NOT_FINALIZED);
        assertThat(t.reason()).isEqualTo("partial: 1/2 succeeded");
    }

    @Test
    void correctorNeverCallsSubmitWhateverTheChannelSays() {
        TaskView v = submitted("sc-11", 3);
        List<OrderView> o = v.orders();
        gateway.answer(o.get(0).orderId(), ChannelQueryResult.NOT_FOUND);
        gateway.answer(o.get(1).orderId(), ChannelQueryResult.PROCESSING);
        gateway.answer(o.get(2).orderId(), ChannelQueryResult.FAILED);
        int submitsBefore = gateway.calls().size();
        for (int i = 0; i < 3; i++) {
            clock.advance(PAST_WINDOW);
            corrector.runOnce();
        }
        assertThat(gateway.calls()).hasSize(submitsBefore);
    }

    @Test
    void callbackAndCorrectorRaceKeepsTheFirstTerminalTruth() {
        TaskView v = submitted("sc-12", 1);
        OrderView o = v.orders().get(0);
        callbacks.onCallback(cb(o, "SUCCESS", 1));
        gateway.answer(o.orderId(), ChannelQueryResult.FAILED);
        clock.advance(PAST_WINDOW);
        assertThat(corrector.runOnce()).isZero();   // terminal: not even queried
        assertThat(orderStatus(o.orderId())).isEqualTo("SUCCESS");
    }
}
