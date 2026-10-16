package com.example.payplat.routing;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SwitchLog {
    public record Entry(String from, String to, String reason) {}

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void record(String from, String to, String reason) {
        entries.add(new Entry(from, to, reason));
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }
}
