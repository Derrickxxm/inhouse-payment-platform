package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationTest extends Pp5TestBase {

    @Test
    void agreeingLedgerAndStatementIsBalancedAndWritesNothing() {
        long a = orderOnB("rc-1a", 700, OrderStatus.SUCCESS);
        long b = orderOnB("rc-1b", 300, OrderStatus.SUCCESS);
        gateway.statement(B, D, List.of(new StatementLine(a, 700, "SUCCESS"), new StatementLine(b, 300, "SUCCESS")));
        ReconReport r = recon.reconcile(D);
        assertThat(r.date()).isEqualTo(D);
        assertThat(r.balanced()).isTrue();
        assertThat(r.diffs()).isEmpty();
        assertThat(count("recon_diff")).isZero();
    }

    @Test
    void channelLineWithoutLocalOrderIsLong() {
        gateway.statement(B, D, List.of(new StatementLine(987654, 1200, "SUCCESS")));
        ReconReport r = recon.reconcile(D);
        assertThat(r.balanced()).isFalse();
        assertThat(only(r)).isEqualTo(new ReconDiff(ReconDiffType.LONG, B, 987654, null, 1200L, null, "SUCCESS"));
    }

    @Test
    void localSuccessMissingFromTheStatementIsShort() {
        long o = orderOnB("rc-3", 450, OrderStatus.SUCCESS);
        ReconReport r = recon.reconcile(D);
        assertThat(only(r)).isEqualTo(new ReconDiff(ReconDiffType.SHORT, B, o, 450L, null, "SUCCESS", null));
        assertThat(r.balanced()).isFalse();
    }

    @Test
    void localNonSuccessMissingFromTheStatementIsNotADiff() {
        orderOnB("rc-4a", 100, OrderStatus.FAILED);
        orderOnB("rc-4b", 100, OrderStatus.SUBMITTED);
        orderOnB("rc-4c", 100, OrderStatus.UNKNOWN);
        assertThat(recon.reconcile(D).balanced()).isTrue();
    }

    @Test
    void differentAmountsAreAmountMismatch() {
        long o = orderOnB("rc-5", 500, OrderStatus.SUCCESS);
        gateway.statement(B, D, List.of(new StatementLine(o, 499, "SUCCESS")));
        assertThat(only(recon.reconcile(D)))
            .isEqualTo(new ReconDiff(ReconDiffType.AMOUNT_MISMATCH, B, o, 500L, 499L, "SUCCESS", "SUCCESS"));
    }

    @Test
    void differentStatusesAreStatusMismatch() {
        long o = orderOnB("rc-6", 500, OrderStatus.SUCCESS);
        gateway.statement(B, D, List.of(new StatementLine(o, 500, "FAILED")));
        assertThat(only(recon.reconcile(D)))
            .isEqualTo(new ReconDiff(ReconDiffType.STATUS_MISMATCH, B, o, 500L, 500L, "SUCCESS", "FAILED"));
    }

    @Test
    void amountAndStatusBothWrongYieldsTwoDiffsAmountFirst() {
        long o = orderOnB("rc-7", 500, OrderStatus.SUCCESS);
        gateway.statement(B, D, List.of(new StatementLine(o, 1, "FAILED")));
        assertThat(recon.reconcile(D).diffs()).extracting(ReconDiff::type)
            .containsExactly(ReconDiffType.AMOUNT_MISMATCH, ReconDiffType.STATUS_MISMATCH);
    }

    @Test
    void inFlightLocalStatesMatchProcessingOnly() {
        long sub = orderOnB("rc-8a", 100, OrderStatus.SUBMITTED);
        long unk = orderOnB("rc-8b", 100, OrderStatus.UNKNOWN);
        long sub2 = orderOnB("rc-8c", 100, OrderStatus.SUBMITTED);
        gateway.statement(B, D, List.of(new StatementLine(sub, 100, "PROCESSING"),
            new StatementLine(unk, 100, "PROCESSING"), new StatementLine(sub2, 100, "SUCCESS")));
        ReconReport r = recon.reconcile(D);
        assertThat(only(r)).isEqualTo(new ReconDiff(ReconDiffType.STATUS_MISMATCH, B, sub2, 100L, 100L, "SUBMITTED", "SUCCESS"));
    }

    @Test
    void ordersFromOtherUtcDatesAreOutsideTheDay() {
        long o = orderOnB("rc-9", 100, OrderStatus.SUCCESS);
        setUpdated(o, "2026-01-02T10:00:00Z");
        assertThat(recon.reconcile(D).balanced()).isTrue();
        assertThat(only(recon.reconcile(D.plusDays(1))).type()).isEqualTo(ReconDiffType.SHORT);
    }

    @Test
    void theDayBoundaryIsUtcMidnight() {
        long late = orderOnB("rc-10a", 100, OrderStatus.SUCCESS);
        long early = orderOnB("rc-10b", 100, OrderStatus.SUCCESS);
        setUpdated(late, "2025-12-31T23:59:59Z");
        setUpdated(early, "2026-01-01T00:00:00Z");
        assertThat(only(recon.reconcile(LocalDate.of(2025, 12, 31))).orderId()).isEqualTo(late);
        assertThat(only(recon.reconcile(D)).orderId()).isEqualTo(early);
    }

    @Test
    void ordersWithoutAChannelAreNotPartOfAnyLocalSide() {
        payments.submit(req("rc-11", 100, com.example.payplat.routing.Tier.CHEAPEST, 1)); // CREATED, channel_id null
        assertThat(recon.reconcile(D).balanced()).isTrue();
    }

    @Test
    void statementIsRequestedOncePerConfiguredChannel() {
        recon.reconcile(D);
        assertThat(gateway.statementCalls()).extracting(ScriptedGateway.StatementCall::channelId)
            .containsExactlyInAnyOrder(A, B, C, W);
        assertThat(gateway.statementCalls()).allSatisfy(c -> assertThat(c.date()).isEqualTo(D));
    }

    @Test
    void sameOrderOnTheWrongChannelStatementIsLongThereAndShortHere() {
        long o = orderOnB("rc-13", 100, OrderStatus.SUCCESS);
        gateway.statement(A, D, List.of(new StatementLine(o, 100, "SUCCESS")));
        ReconReport r = recon.reconcile(D);
        assertThat(r.diffs()).extracting(ReconDiff::type, ReconDiff::channelId)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(ReconDiffType.LONG, A),
                org.assertj.core.groups.Tuple.tuple(ReconDiffType.SHORT, B));
    }

    @Test
    void diffsAreOrderedByChannelThenOrderIdThenType() {
        long o1 = orderOnB("rc-14a", 100, OrderStatus.SUCCESS);
        long o2 = orderOnB("rc-14b", 100, OrderStatus.SUCCESS);
        gateway.statement(W, D, List.of(new StatementLine(5, 1, "SUCCESS")));
        gateway.statement(A, D, List.of(new StatementLine(9, 1, "SUCCESS")));
        List<ReconDiff> diffs = recon.reconcile(D).diffs();
        assertThat(diffs).extracting(ReconDiff::channelId, ReconDiff::orderId)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(A, 9L), org.assertj.core.groups.Tuple.tuple(B, o1),
                org.assertj.core.groups.Tuple.tuple(B, o2), org.assertj.core.groups.Tuple.tuple(W, 5L));
    }

    @Test
    void diffsAreStoredInReconDiff() {
        long o = orderOnB("rc-15", 450, OrderStatus.SUCCESS);
        gateway.statement(B, D, List.of(new StatementLine(777, 5, "FAILED")));
        recon.reconcile(D);
        var rows = reconRows(D);
        assertThat(rows).hasSize(2);
        // rows follow the diff order: orderId ascending within the channel, so the real order comes first
        assertThat(rows.get(0)).containsEntry("TYPE", "SHORT").containsEntry("ORDER_ID", o).containsEntry("LOCAL_AMOUNT", 450L)
            .containsEntry("LOCAL_STATUS", "SUCCESS").containsEntry("CHANNEL_ID", B);
        assertThat(rows.get(0).get("CHANNEL_AMOUNT")).isNull();
        assertThat(rows.get(1)).containsEntry("TYPE", "LONG").containsEntry("CHANNEL_ID", B)
            .containsEntry("ORDER_ID", 777L).containsEntry("CHANNEL_AMOUNT", 5L).containsEntry("CHANNEL_STATUS", "FAILED");
        assertThat(rows.get(1).get("LOCAL_AMOUNT")).isNull();
        assertThat(rows.get(1).get("CREATED_AT")).isNotNull();
    }

    @Test
    void rerunningADateReplacesItsRowsAndLeavesOtherDatesAlone() {
        long o = orderOnB("rc-16", 100, OrderStatus.SUCCESS);
        setUpdated(o, "2026-01-02T01:00:00Z");
        gateway.statement(B, D, List.of(new StatementLine(42, 5, "SUCCESS")));
        recon.reconcile(D);
        recon.reconcile(D);
        assertThat(reconRows(D)).hasSize(1);
        recon.reconcile(D.plusDays(1)); // SHORT for the moved order
        assertThat(reconRows(D.plusDays(1))).hasSize(1);
        gateway.statement(B, D, List.of());
        assertThat(recon.reconcile(D).balanced()).isTrue();
        assertThat(reconRows(D)).isEmpty();
        assertThat(reconRows(D.plusDays(1))).hasSize(1);
    }

    @Test
    void gatewayFailureFailsFastAndKeepsEarlierRows() {
        gateway.statement(B, D, List.of(new StatementLine(42, 5, "SUCCESS")));
        recon.reconcile(D);
        gateway.failStatements();
        assertThatThrownBy(() -> recon.reconcile(D)).isInstanceOf(IllegalStateException.class);
        assertThat(reconRows(D)).hasSize(1);
    }

    @Test
    void defaultGatewayFailsFastOnStatement() {
        ChannelGateway def = new GatewayConfiguration().channelGateway();
        assertThatThrownBy(() -> def.statement(B, D)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportDiffsAreUnmodifiable() {
        ReconReport r = recon.reconcile(D);
        assertThat(r.diffs()).isNotNull();
        assertThatThrownBy(() -> r.diffs().add(new ReconDiff(ReconDiffType.LONG, B, 1, null, 1L, null, "SUCCESS")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
