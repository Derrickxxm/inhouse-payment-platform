package com.example.payplat.payment;

import com.example.payplat.routing.ChannelRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    private final ChannelGateway gateway;
    private final ChannelRegistry channels;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReconciliationService(JdbcTemplate jdbc, ChannelGateway gateway, ChannelRegistry channels,
                                 Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.channels = channels;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReconReport reconcile(LocalDate date) {
        Map<String, List<StatementLine>> statements = new LinkedHashMap<>();
        for (var channel : channels.all()) {
            statements.put(channel.id(), gateway.statement(channel.id(), date));
        }
        List<ReconDiff> diffs = new ArrayList<>();
        statements.forEach((channel, lines) -> {
            Map<Long, LocalOrder> local = new LinkedHashMap<>();
            jdbc.query("SELECT id, amount_cents, status, updated_at FROM payment_order WHERE channel_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    if (rs.getTimestamp("updated_at").toInstant().atZone(ZoneOffset.UTC).toLocalDate().equals(date)) {
                        local.put(rs.getLong("id"), new LocalOrder(rs.getLong("amount_cents"), rs.getString("status")));
                    }
                }, channel);
            for (StatementLine line : lines) {
                LocalOrder order = local.remove(line.orderId());
                if (order == null) {
                    diffs.add(new ReconDiff(ReconDiffType.LONG, channel, line.orderId(), null,
                        line.amountCents(), null, line.status()));
                } else {
                    if (order.amount() != line.amountCents()) {
                        diffs.add(diff(ReconDiffType.AMOUNT_MISMATCH, channel, line, order));
                    }
                    boolean equivalent = switch (order.status()) {
                        case "SUCCESS", "FAILED" -> order.status().equals(line.status());
                        case "SUBMITTED", "UNKNOWN" -> "PROCESSING".equals(line.status());
                        default -> false;
                    };
                    if (!equivalent) diffs.add(diff(ReconDiffType.STATUS_MISMATCH, channel, line, order));
                }
            }
            local.forEach((id, order) -> {
                if ("SUCCESS".equals(order.status())) {
                    diffs.add(new ReconDiff(ReconDiffType.SHORT, channel, id, order.amount(), null, order.status(), null));
                }
            });
        });
        diffs.sort(Comparator.comparing(ReconDiff::channelId).thenComparingLong(ReconDiff::orderId)
            .thenComparing(ReconDiff::type));
        transaction.executeWithoutResult(tx -> {
            jdbc.update("DELETE FROM recon_diff WHERE recon_date = ?", java.sql.Date.valueOf(date));
            Timestamp now = Timestamp.from(clock.instant());
            for (ReconDiff diff : diffs) {
                jdbc.update("INSERT INTO recon_diff (recon_date, type, channel_id, order_id, local_amount, "
                    + "channel_amount, local_status, channel_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Date.valueOf(date), diff.type().name(), diff.channelId(), diff.orderId(),
                    diff.localAmount(), diff.channelAmount(), diff.localStatus(), diff.channelStatus(), now);
            }
        });
        return new ReconReport(date, diffs, diffs.isEmpty());
    }

    private ReconDiff diff(ReconDiffType type, String channel, StatementLine line, LocalOrder order) {
        return new ReconDiff(type, channel, line.orderId(), order.amount(), line.amountCents(), order.status(), line.status());
    }

    private record LocalOrder(long amount, String status) {}
}
