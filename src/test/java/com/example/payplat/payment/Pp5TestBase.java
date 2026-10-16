package com.example.payplat.payment;

import com.example.payplat.routing.Tier;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Shared set-up for PP-5 tests. CHEAPEST routes to FakeBankB; the test clock starts on 2026-01-01 UTC. */
abstract class Pp5TestBase extends Pp3TestBase {
    static final LocalDate D = LocalDate.of(2026, 1, 1);
    @org.springframework.beans.factory.annotation.Autowired ReconciliationService recon;
    @org.springframework.beans.factory.annotation.Autowired MonitoringService monitoring;

    /** One-order task of {@code cents} submitted by the worker on FakeBankB, then driven to {@code target}. */
    long orderOnB(String bizId, long cents, OrderStatus target) {
        TaskView v = payments.submit(req(bizId, cents, Tier.CHEAPEST, 1));
        worker.runOnce();
        long id = v.orders().get(0).orderId();
        if (target != OrderStatus.SUBMITTED) drive(id, target);
        return id;
    }

    void setUpdated(long orderId, String isoInstant) {
        jdbc.update("update payment_order set updated_at = ? where id = ?", Timestamp.from(Instant.parse(isoInstant)), orderId);
    }

    List<Map<String, Object>> reconRows(LocalDate date) {
        return jdbc.queryForList("select * from recon_diff where recon_date = ? order by id", java.sql.Date.valueOf(date));
    }

    ReconDiff only(ReconReport r) {
        org.assertj.core.api.Assertions.assertThat(r.diffs()).hasSize(1);
        return r.diffs().get(0);
    }
}
