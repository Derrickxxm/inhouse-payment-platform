package com.example.payplat.payment;

import com.example.payplat.routing.Tier;
import com.example.payplat.routing.TestClockConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Real commits (no @Transactional on tests); fresh context and fresh in-memory DB per test. */
@SpringBootTest
@Import({TestClockConfig.class, PaymentTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(locations = "classpath:test-channels.properties")
abstract class PaymentTestBase {
    static final String A = "FakeBankA";
    static final String B = "FakeBankB";
    static final String C = "FakeBankC";
    static final String W = "FakeWallet";

    @Autowired PaymentService payments;
    @Autowired JobWorker worker;
    @Autowired TaskService tasks;
    @Autowired OrderStateService orderStates;
    @Autowired ScriptedGateway gateway;
    @Autowired JdbcTemplate jdbc;

    static DebitRequest req(String id, long amount, Tier tier, int split) {
        return new DebitRequest(id, "user-1", amount, tier, split);
    }

    long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    List<Long> orderIds(long taskId) {
        return jdbc.queryForList("select id from payment_order where task_id = ? order by id", Long.class, taskId);
    }

    String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from payment_order where id = ?", String.class, orderId);
    }

    /** Moves an order to {@code target} through legal transitions only (CREATED goes via SUBMITTED). */
    void drive(long orderId, OrderStatus target) {
        OrderStatus cur = OrderStatus.valueOf(orderStatus(orderId));
        if (cur == target) return;
        if (cur == OrderStatus.CREATED && target != OrderStatus.SUBMITTED) orderStates.apply(orderId, OrderStatus.SUBMITTED);
        orderStates.apply(orderId, target);
    }

    /** Writes a status directly with SQL, bypassing the state machine (exhaustive/illegal-state set-up only). */
    void force(long orderId, OrderStatus status) {
        jdbc.update("update payment_order set status = ? where id = ?", status.name(), orderId);
    }
}
