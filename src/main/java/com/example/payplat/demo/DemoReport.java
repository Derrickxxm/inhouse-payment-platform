package com.example.payplat.demo;

import java.util.List;
import java.util.Map;

public record DemoReport(List<ScenarioResult> scenarios) {
    public DemoReport { scenarios = List.copyOf(scenarios); }

    public ScenarioResult scenario(int number) {
        return scenarios.stream().filter(s -> s.number() == number).findFirst().orElseThrow();
    }

    public boolean allPassed() { return scenarios.stream().allMatch(ScenarioResult::passed); }

    public String resultLine() {
        return allPassed() ? "DEMO RESULT: ALL SCENARIOS PASSED" : "DEMO RESULT: FAILED "
            + scenarios.stream().filter(s -> !s.passed()).findFirst().orElseThrow().label();
    }

    public record ScenarioResult(int number, String name, boolean passed, String detail, Map<String, Object> facts) {
        public ScenarioResult { facts = facts == null ? Map.of() : Map.copyOf(facts); }
        public String label() { return "[" + number + "] " + name; }
    }
}
