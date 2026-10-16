package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecoveryServiceTest extends Pp4TestBase {

    @Test
    void emptyDatabaseRecoversNothing() {
        RecoveryReport r = recovery.recover();
        assertThat(r).isEqualTo(new RecoveryReport(0, 0, 0));
        assertThat(gateway.queries()).isEmpty();
        assertThat(recoveryEvents()).isZero();
    }

    @Test
    void stuckOrderThatTheChannelSucceededIsAppliedWithoutResubmitting() {
        long o = freshOrder("rec-1");
        stuckInFlight(o, B);
        gateway.answer(o, ChannelQueryResult.SUCCESS);
        pastWindow();
        RecoveryReport r = recovery.recover();
        assertThat(r).isEqualTo(new RecoveryReport(0, 1, 0));
        assertThat(orderStatus(o)).isEqualTo("SUCCESS");
        assertThat(jobStatus(o)).isEqualTo("DONE");
        assertThat(task(taskIdOf(o)).status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(gateway.queriesFor(o)).isEqualTo(1);
        assertThat(gateway.queries().get(0).channelId()).isEqualTo(B);
        assertThat(gateway.calls()).isEmpty();
        assertThat(eventSources(o)).containsOnly("RECOVERY");
    }

    @Test
    void stuckOrderThatTheChannelFailedBecomesFailed() {
        long o = freshOrder("rec-2");
        stuckInFlight(o, B);
        gateway.answer(o, ChannelQueryResult.FAILED);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 1, 0));
        assertThat(orderStatus(o)).isEqualTo("FAILED");
        assertThat(jobStatus(o)).isEqualTo("DONE");
        assertThat(task(taskIdOf(o)).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    void stuckOrderStillProcessingBecomesSubmittedAndTheJobIsClosed() {
        long o = freshOrder("rec-3");
        stuckInFlight(o, B);
        gateway.answer(o, ChannelQueryResult.PROCESSING);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 1, 0));
        assertThat(orderStatus(o)).isEqualTo("SUBMITTED");
        assertThat(jobStatus(o)).isEqualTo("DONE");
        assertThat(worker.runOnce()).isZero();
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    void notFoundRequeuesTheJobAndTheWorkerResubmitsExactlyOnce() {
        long o = freshOrder("rec-4");
        stuckInFlight(o, B);
        gateway.answer(o, ChannelQueryResult.NOT_FOUND);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(1, 0, 0));
        assertThat(orderStatus(o)).isEqualTo("CREATED");
        assertThat(jobStatus(o)).isEqualTo("PENDING");
        assertThat(gateway.calls()).as("recovery itself never submits").isEmpty();
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(gateway.callsFor(o)).isEqualTo(1);
        assertThat(orderStatus(o)).isEqualTo("SUBMITTED");
        assertThat(worker.runOnce()).isZero();
        assertThat(gateway.callsFor(o)).isEqualTo(1);
    }

    @Test
    void orderWithoutAChannelNeverReachedOneSoItIsRequeuedWithoutAQuery() {
        long o = freshOrder("rec-5");
        stuckInFlight(o, B);
        jdbc.update("update payment_order set channel_id = null, submitting_at = null where id = ?", o);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(1, 0, 0));
        assertThat(gateway.queries()).isEmpty();
        assertThat(jobStatus(o)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select channel_answer from recovery_event where order_id = ?", String.class, o)).isNull();
    }

    @Test
    void runningJobInsideTheWindowIsNotTouched() {
        long o = freshOrder("rec-6");
        stuckInFlight(o, B);
        gateway.answer(o, ChannelQueryResult.SUCCESS);
        clock.advance(Duration.ofSeconds(119));
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 0));
        assertThat(gateway.queries()).isEmpty();
        assertThat(jobStatus(o)).isEqualTo("RUNNING");
        assertThat(orderStatus(o)).isEqualTo("CREATED");
    }

    @Test
    void exactlyAtTheWindowIsNotYetStale() {
        long o = freshOrder("rec-7");
        stuckInFlight(o, B);
        clock.advance(Duration.ofSeconds(120));
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 0));
        clock.advance(Duration.ofSeconds(1));
        gateway.answer(o, ChannelQueryResult.NOT_FOUND);
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(1, 0, 0));
    }

    @Test
    void pendingAndTerminalOrdersAreNeverCandidates() {
        TaskView done = submitted("rec-8b", 1);
        long finished = done.orders().get(0).orderId();
        long pending = freshOrder("rec-8a");
        drive(finished, OrderStatus.SUCCESS);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 0));
        assertThat(gateway.queries()).isEmpty();
        assertThat(jobStatus(pending)).isEqualTo("PENDING");
    }

    @Test
    void staleSubmittedOrderIsCorrectedByTheChannelAnswer() {
        long o = submitted("rec-9", 1).orders().get(0).orderId();
        gateway.answer(o, ChannelQueryResult.SUCCESS);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 1, 0));
        assertThat(orderStatus(o)).isEqualTo("SUCCESS");
        assertThat(eventSources(o)).contains("RECOVERY");
        assertThat(gateway.calls()).hasSize(1); // only the original worker submit
    }

    @Test
    void staleSubmittedOrderStillProcessingIsLeftUntouched() {
        long o = submitted("rec-10", 1).orders().get(0).orderId();
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 1));
        assertThat(orderStatus(o)).isEqualTo("SUBMITTED");
    }

    @Test
    void notFoundOnSubmittedMovesToUnknownAndOnUnknownChangesNothing() {
        long o = submitted("rec-11", 1).orders().get(0).orderId();
        gateway.answer(o, ChannelQueryResult.NOT_FOUND);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 1, 0));
        assertThat(orderStatus(o)).isEqualTo("UNKNOWN");
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 1));
        assertThat(orderStatus(o)).isEqualTo("UNKNOWN");
        assertThat(gateway.callsFor(o)).isEqualTo(1);
    }

    @Test
    void recoverTwiceIsIdempotent() {
        long a = freshOrder("rec-12a");
        long b = freshOrder("rec-12b");
        stuckInFlight(a, B);
        stuckInFlight(b, B);
        gateway.answer(a, ChannelQueryResult.SUCCESS);
        gateway.answer(b, ChannelQueryResult.NOT_FOUND);
        pastWindow();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(1, 1, 0));
        long queries = gateway.queries().size();
        long stateEvents = count("state_event");
        long recEvents = recoveryEvents();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 0));
        assertThat(gateway.queries()).hasSize((int) queries);
        assertThat(count("state_event")).isEqualTo(stateEvents);
        assertThat(recoveryEvents()).isEqualTo(recEvents);
    }

    @Test
    void oneRecoveryEventPerCandidateWithTheChannelAnswer() {
        long a = freshOrder("rec-13a");
        long b = freshOrder("rec-13b");
        stuckInFlight(a, B);
        stuckInFlight(b, B);
        gateway.answer(a, ChannelQueryResult.SUCCESS);
        gateway.answer(b, ChannelQueryResult.NOT_FOUND);
        pastWindow();
        recovery.recover();
        assertThat(recoveryEvents()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select action from recovery_event where order_id = ?", String.class, a)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select channel_answer from recovery_event where order_id = ?", String.class, a)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("select action from recovery_event where order_id = ?", String.class, b)).isEqualTo("REQUEUED");
    }

    @Test
    void recoveryNeverSubmitsAcrossAMixOfStates() {
        long a = freshOrder("rec-14a");
        long b = freshOrder("rec-14b");
        long c = freshOrder("rec-14c");
        stuckInFlight(a, B);
        stuckInFlight(b, B);
        stuckInFlight(c, B);
        gateway.answer(a, ChannelQueryResult.SUCCESS);
        gateway.answer(b, ChannelQueryResult.PROCESSING);
        gateway.answer(c, ChannelQueryResult.NOT_FOUND);
        pastWindow();
        recovery.recover();
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    void workerPersistsChannelAndMarkerBeforeCallingTheGateway() {
        long o = freshOrder("rec-15");
        java.util.List<Object[]> seen = new java.util.ArrayList<>();
        gateway.onCall(call -> {
            var row = jdbc.queryForMap("select channel_id, submitting_at, status from payment_order where id = ?", call.orderId());
            seen.add(new Object[] {call.channelId(), row.get("CHANNEL_ID"), row.get("SUBMITTING_AT"), row.get("STATUS")});
        });
        worker.runOnce();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)[1]).as("channel_id already persisted").isEqualTo(seen.get(0)[0]);
        assertThat(seen.get(0)[2]).as("submitting marker already persisted").isNotNull();
        assertThat(seen.get(0)[3]).as("still CREATED during the call").isEqualTo("CREATED");
        assertThat(orderStatus(o)).isEqualTo("SUBMITTED");
    }

    @Test
    void failoverPersistsEachChannelBeforeItsOwnSubmit() {
        gateway.reject(C);
        long o = payments.submit(req("rec-16", 400, com.example.payplat.routing.Tier.FASTEST, 1)).orders().get(0).orderId();
        java.util.List<String> persisted = new java.util.ArrayList<>();
        gateway.onCall(call -> persisted.add(jdbc.queryForObject(
            "select channel_id from payment_order where id = ?", String.class, call.orderId())));
        worker.runOnce();
        assertThat(persisted).containsExactly(C, A);
        assertThat(orderStatus(o)).isEqualTo("SUBMITTED");
    }

    @Test
    void claimingAJobStampsStartedAt() {
        long o = freshOrder("rec-17");
        java.util.List<Object> started = new java.util.ArrayList<>();
        gateway.onCall(call -> started.add(jdbc.queryForObject(
            "select started_at from job where order_id = ?", java.sql.Timestamp.class, call.orderId())));
        worker.runOnce();
        assertThat(started).hasSize(1).doesNotContainNull();
    }
}
