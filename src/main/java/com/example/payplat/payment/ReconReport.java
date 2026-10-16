package com.example.payplat.payment;

import java.time.LocalDate;
import java.util.List;

public record ReconReport(LocalDate date, List<ReconDiff> diffs, boolean balanced) {
    public ReconReport {
        diffs = List.copyOf(diffs);
        balanced = diffs.isEmpty();
    }
}
