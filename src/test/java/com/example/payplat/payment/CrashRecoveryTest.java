package com.example.payplat.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payplat.routing.TestClock;
import com.example.payplat.routing.Tier;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real restart: two (or three) sequential Spring contexts over ONE file-based H2 database, one JVM-static channel world.
 * The first context "dies" by an exception thrown inside the gateway; its connections are then closed.
 */
class CrashRecoveryTest {
    @TempDir Path dir;
    private ConfigurableApplicationContext ctx;

    @BeforeEach
    void freshWorld() { ChannelWorld.reset(); }

    @AfterEach
    void closeContext() { if (ctx != null) ctx.close(); }

    private ConfigurableApplicationContext restart(Duration offset, boolean onStartup) {
        if (ctx != null) ctx.close();
        ctx = CrashApp.start(dir, offset, onStartup);
        return ctx;
    }

    private static List<Long> submitThree(ConfigurableApplicationContext c, String biz) {
        TaskView v = c.getBean(PaymentService.class).submit(new DebitRequest(biz, "user-1", 900, Tier.CHEAPEST, 3));
        return v.orders().stream().map(OrderView::orderId).toList();
    }

    private static long taskId(ConfigurableApplicationContext c) {
        return c.getBean(JdbcTemplate.class).queryForObject("select max(id) from task", Long.class);
    }

    private static String orderStatus(ConfigurableApplicationContext c, long id) {
        return c.getBean(JdbcTemplate.class).queryForObject("select status from payment_order where id = ?", String.class, id);
    }

    private static String jobStatus(ConfigurableApplicationContext c, long id) {
        return c.getBean(JdbcTemplate.class).queryForObject("select status from job where order_id = ?", String.class, id);
    }

    private void finishWithCorrector(List<Long> orders) {
        ctx.getBean(TestClock.class).advance(Duration.ofSeconds(61));
        ctx.getBean(StatusCorrector.class).runOnce();
        TaskView task = ctx.getBean(PaymentService.class).get(taskId(ctx));
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.orders()).extracting(OrderView::status).containsOnly(OrderStatus.SUCCESS);
        assertThat(task.orders()).hasSameSizeAs(orders);
    }

    @Test
    void crashAfterTheChannelAcceptedNeverSubmitsThatOrderAgain() {
        ChannelWorld.acceptedAnswer(ChannelQueryResult.SUCCESS);
        restart(Duration.ZERO, false);
        List<Long> orders = submitThree(ctx, "crash-1");
        ChannelWorld.crashAfterAccept(orders.get(0));
        assertThatThrownBy(() -> ctx.getBean(JobWorker.class).runOnce()).isInstanceOf(ChannelWorld.SimulatedCrash.class);
        assertThat(orderStatus(ctx, orders.get(0))).isEqualTo("CREATED");
        assertThat(jobStatus(ctx, orders.get(0))).isEqualTo("RUNNING");

        restart(Duration.ofSeconds(121), false);
        assertThat(ctx.getBean(RecoveryService.class).recover()).isEqualTo(new RecoveryReport(0, 1, 0));
        assertThat(orderStatus(ctx, orders.get(0))).isEqualTo("SUCCESS");
        ctx.getBean(JobWorker.class).runOnce();
        finishWithCorrector(orders);
        for (long o : orders) {
            assertThat(ChannelWorld.invocations(o)).as("submit calls for order " + o).isEqualTo(1);
            assertThat(ChannelWorld.accepted(o)).as("debits for order " + o).isEqualTo(1);
        }
    }

    @Test
    void crashBeforeTheChannelSawTheOrderResubmitsItExactlyOnceMore() {
        ChannelWorld.acceptedAnswer(ChannelQueryResult.SUCCESS);
        restart(Duration.ZERO, false);
        List<Long> orders = submitThree(ctx, "crash-2");
        ChannelWorld.crashBeforeAccept(orders.get(0));
        assertThatThrownBy(() -> ctx.getBean(JobWorker.class).runOnce()).isInstanceOf(ChannelWorld.SimulatedCrash.class);
        assertThat(ctx.getBean(JdbcTemplate.class).queryForObject(
            "select channel_id from payment_order where id = ?", String.class, orders.get(0)))
            .as("the marker survived the crash").isNotNull();

        restart(Duration.ofSeconds(121), false);
        assertThat(ctx.getBean(RecoveryService.class).recover()).isEqualTo(new RecoveryReport(1, 0, 0));
        assertThat(ChannelWorld.invocations(orders.get(0))).as("recovery does not submit").isEqualTo(1);
        assertThat(ctx.getBean(JobWorker.class).runOnce()).isEqualTo(3);
        assertThat(ChannelWorld.invocations(orders.get(0))).isEqualTo(2);
        assertThat(ChannelWorld.accepted(orders.get(0))).isEqualTo(1);
        assertThat(ChannelWorld.invocations(orders.get(1))).isEqualTo(1);
        assertThat(ChannelWorld.invocations(orders.get(2))).isEqualTo(1);
        finishWithCorrector(orders);
        for (long o : orders) assertThat(ChannelWorld.accepted(o)).as("debits for order " + o).isEqualTo(1);
    }

    @Test
    void startupHookRecoversWithoutAnyExplicitCall() {
        ChannelWorld.acceptedAnswer(ChannelQueryResult.PROCESSING);
        restart(Duration.ZERO, false);
        List<Long> orders = submitThree(ctx, "crash-3");
        ChannelWorld.crashAfterAccept(orders.get(0));
        assertThatThrownBy(() -> ctx.getBean(JobWorker.class).runOnce()).isInstanceOf(ChannelWorld.SimulatedCrash.class);

        restart(Duration.ofSeconds(121), true);
        assertThat(orderStatus(ctx, orders.get(0))).isEqualTo("SUBMITTED");
        assertThat(jobStatus(ctx, orders.get(0))).isEqualTo("DONE");
        assertThat(ctx.getBean(JobWorker.class).runOnce()).isEqualTo(2);
        assertThat(ChannelWorld.invocations(orders.get(0))).isEqualTo(1);
    }

    @Test
    void startupHookCanBeSwitchedOff() {
        ChannelWorld.acceptedAnswer(ChannelQueryResult.SUCCESS);
        restart(Duration.ZERO, false);
        List<Long> orders = submitThree(ctx, "crash-4");
        ChannelWorld.crashAfterAccept(orders.get(0));
        assertThatThrownBy(() -> ctx.getBean(JobWorker.class).runOnce()).isInstanceOf(ChannelWorld.SimulatedCrash.class);

        restart(Duration.ofSeconds(121), false);
        assertThat(orderStatus(ctx, orders.get(0))).isEqualTo("CREATED");
        assertThat(jobStatus(ctx, orders.get(0))).isEqualTo("RUNNING");
        assertThat(ChannelWorld.queries()).isZero();
    }

    @Test
    void recoveryIsIdempotentAcrossRestartsAndRepeatedCalls() {
        ChannelWorld.acceptedAnswer(ChannelQueryResult.SUCCESS);
        restart(Duration.ZERO, false);
        List<Long> orders = submitThree(ctx, "crash-5");
        ChannelWorld.crashAfterAccept(orders.get(0));
        assertThatThrownBy(() -> ctx.getBean(JobWorker.class).runOnce()).isInstanceOf(ChannelWorld.SimulatedCrash.class);

        restart(Duration.ofSeconds(121), false);
        RecoveryService recovery = ctx.getBean(RecoveryService.class);
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 1, 0));
        int queries = ChannelWorld.queries();
        assertThat(recovery.recover()).isEqualTo(new RecoveryReport(0, 0, 0));
        assertThat(ChannelWorld.queries()).isEqualTo(queries);

        restart(Duration.ofSeconds(121), true);
        assertThat(ChannelWorld.queries()).as("a later startup finds nothing to recover").isEqualTo(queries);
        assertThat(ctx.getBean(JdbcTemplate.class).queryForObject(
            "select count(*) from state_event where order_id = ? and outcome = 'APPLIED'", Long.class, orders.get(0)))
            .isEqualTo(2); // CREATED->SUBMITTED, SUBMITTED->SUCCESS, once
    }

    @Test
    void taskStillFinishesSucceededWithEachOrderDebitedOnce() {
        ChannelWorld.acceptedAnswer(ChannelQueryResult.SUCCESS);
        restart(Duration.ZERO, false);
        List<Long> orders = submitThree(ctx, "crash-6");
        ChannelWorld.crashAfterAccept(orders.get(1));
        assertThatThrownBy(() -> ctx.getBean(JobWorker.class).runOnce()).isInstanceOf(ChannelWorld.SimulatedCrash.class);

        restart(Duration.ofSeconds(121), false);
        ctx.getBean(RecoveryService.class).recover();
        ctx.getBean(RecoveryService.class).recover();
        ctx.getBean(JobWorker.class).runOnce();
        ctx.getBean(JobWorker.class).runOnce();
        finishWithCorrector(orders);
        assertThat(orders).allSatisfy(o -> assertThat(ChannelWorld.accepted(o)).isEqualTo(1));
        assertThat(ctx.getBean(JdbcTemplate.class).queryForObject(
            "select sum(amount_cents) from payment_order where status = 'SUCCESS'", Long.class)).isEqualTo(900L);
    }
}
