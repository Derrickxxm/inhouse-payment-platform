package com.example.payplat.routing;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SwitchLog {
    public record Entry(String from, String to, String reason) {}
    public record Row(Long taskId, Long orderId, String from, String to, String reason) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SwitchLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String from, String to, String reason) {
        record(null, null, from, to, reason);
    }

    public void record(Long taskId, Long orderId, String from, String to, String reason) {
        jdbc.update("INSERT INTO channel_switch_log (task_id, order_id, from_channel, to_channel, reason, at) "
            + "VALUES (?, ?, ?, ?, ?, ?)", taskId, orderId, from, to, reason, Timestamp.from(clock.instant()));
    }

    public List<Entry> entries() {
        return rows().stream().map(r -> new Entry(r.from(), r.to(), r.reason())).toList();
    }

    public List<Row> rows() {
        return List.copyOf(jdbc.query("SELECT * FROM channel_switch_log ORDER BY id", (rs, n) ->
            new Row(rs.getObject("task_id", Long.class), rs.getObject("order_id", Long.class),
                rs.getString("from_channel"), rs.getString("to_channel"), rs.getString("reason"))));
    }
}
