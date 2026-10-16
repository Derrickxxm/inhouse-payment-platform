package com.example.payplat.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MonitoringService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, ChannelStats> snapshot() {
        Map<String, List<Call>> calls = new TreeMap<>();
        jdbc.query("SELECT channel_id, accepted, reason, duration_ms FROM gateway_call_log",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                calls.computeIfAbsent(rs.getString("channel_id"), key -> new ArrayList<>())
                    .add(new Call(rs.getBoolean("accepted"), rs.getString("reason"), rs.getLong("duration_ms"))));
        Map<String, ChannelStats> result = new TreeMap<>();
        calls.forEach((channel, rows) -> {
            long successes = rows.stream().filter(Call::accepted).count();
            List<Long> durations = rows.stream().map(Call::duration).sorted().toList();
            Map<String, Long> reasons = new TreeMap<>();
            rows.stream().filter(row -> !row.accepted()).forEach(row ->
                reasons.merge(row.reason() == null ? "UNKNOWN" : row.reason(), 1L, Long::sum));
            result.put(channel, new ChannelStats(rows.size(), successes, (double) successes / rows.size(),
                percentile(durations, 50), percentile(durations, 95), reasons));
        });
        return result;
    }

    public String toJson() {
        try {
            return mapper.writeValueAsString(snapshot());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize channel statistics", e);
        }
    }

    private long percentile(List<Long> sorted, int percent) {
        return sorted.get((int) Math.ceil(percent / 100.0 * sorted.size()) - 1);
    }

    private record Call(boolean accepted, String reason, long duration) {}
}
