package com.example.payplat.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payplat.demo.DemoReport.ScenarioResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One real demo run (eight scenarios, real Spring contexts, file H2) shared by all assertions below. */
class DemoRunnerTest {
    @TempDir static Path work;
    static DemoReport report;
    static String output;

    @BeforeAll
    static void runDemoOnce() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) { captured.write(b); original.write(b); }
            @Override public void write(byte[] b, int off, int len) { captured.write(b, off, len); original.write(b, off, len); }
        }, true, StandardCharsets.UTF_8));
        try {
            report = new DemoRunner().run(work);
        } finally {
            System.setOut(original);
        }
        output = captured.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> facts(int n) { return report.scenario(n).facts(); }

    private static long num(int n, String key) {
        Object value = facts(n).get(key);
        assertThat(value).as("fact " + key + " of scenario " + n).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    private static List<Long> nums(int n, String key) {
        Object value = facts(n).get(key);
        assertThat(value).as("fact " + key + " of scenario " + n).isInstanceOf(List.class);
        return ((List<?>) value).stream().map(v -> ((Number) v).longValue()).toList();
    }

    private static Set<String> strings(int n, String key) {
        Object value = facts(n).get(key);
        assertThat(value).as("fact " + key + " of scenario " + n).isInstanceOf(java.util.Collection.class);
        Set<String> out = new HashSet<>();
        for (Object o : (java.util.Collection<?>) value) out.add(String.valueOf(o));
        return out;
    }

    @Test
    void reportHasEightScenariosAllPassingInOrderWithTheAgreedNames() {
        assertThat(report.scenarios()).extracting(ScenarioResult::number).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(report.scenarios()).extracting(ScenarioResult::name).containsExactly(
            "onboard-and-debit", "duplicate-submit", "callback-chaos", "channel-failover",
            "partial-success", "crash-recovery", "reconciliation-differences", "monitoring");
        for (ScenarioResult s : report.scenarios()) assertThat(s.passed()).as(s.label() + ": " + s.detail()).isTrue();
        assertThat(report.allPassed()).isTrue();
        assertThat(report.resultLine()).isEqualTo("DEMO RESULT: ALL SCENARIOS PASSED");
    }

    @Test
    void resultLineNamesTheFirstFailedScenario() {
        DemoReport failing = new DemoReport(List.of(
            new ScenarioResult(1, "onboard-and-debit", true, "ok", Map.of()),
            new ScenarioResult(2, "duplicate-submit", false, "two debits", Map.of()),
            new ScenarioResult(3, "callback-chaos", false, "x", Map.of())));
        assertThat(failing.allPassed()).isFalse();
        assertThat(failing.resultLine()).isEqualTo("DEMO RESULT: FAILED [2] duplicate-submit");
        assertThat(failing.scenario(2).label()).isEqualTo("[2] duplicate-submit");
    }

    @Test
    void printedSectionsAppearInOrderAndTheFinalLineIsLast() {
        List<String> lines = output.lines().toList();
        List<Integer> headers = lines.stream().filter(l -> l.matches("^\\[[1-8]\\] .+"))
            .map(l -> l.charAt(1) - '0').toList();
        assertThat(headers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(lines.stream().filter(l -> l.equals("    RESULT: PASS")).count()).isEqualTo(8);
        assertThat(lines.stream().filter(l -> l.startsWith("DEMO RESULT")).toList())
            .containsExactly("DEMO RESULT: ALL SCENARIOS PASSED");
        List<String> nonBlank = lines.stream().filter(l -> !l.isBlank()).toList();
        assertThat(nonBlank.getLast()).isEqualTo("DEMO RESULT: ALL SCENARIOS PASSED");
    }

    @Test
    void eachScenarioUsesItsOwnFreshFileDatabase() throws IOException {
        for (int n = 1; n <= 8; n++) {
            assertThat(work.resolve("scenario-" + n).resolve("payplat.mv.db")).as("database of scenario " + n).exists();
        }
    }

    @Test
    void newChannelIsOnboardedByYamlOnlyAndWinsTheFastestTier() throws IOException {
        assertThat(facts(1)).containsEntry("fastestBefore", "FakeBankC").containsEntry("fastestAfter", "FakeBankD")
            .containsEntry("chosenChannel", "FakeBankD").containsEntry("tier", "FASTEST")
            .containsEntry("callbackOutcome", "APPLIED").containsEntry("taskStatus", "SUCCEEDED")
            .containsEntry("reconBalanced", true);
        assertThat(num(1, "reconDiffCount")).isZero();
        assertThat(resource("/demo-channels.yml")).contains("id: FakeBankD").contains("id: FakeBankC");
        assertThat(resource("/channels.yml")).doesNotContain("FakeBankD");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try { return Files.readString(p).contains("FakeBankD"); } catch (IOException e) { throw new RuntimeException(e); }
            }).toList();
            assertThat(offenders).as("Java code must not know the onboarded channel").isEmpty();
        }
    }

    @Test
    void duplicateSubmitDebitsExactlyOnce() {
        assertThat(num(2, "taskIdFirst")).isEqualTo(num(2, "taskIdSecond"));
        assertThat(num(2, "taskCount")).isEqualTo(1);
        assertThat(num(2, "gatewaySubmitCount")).isEqualTo(1);
        assertThat(num(2, "debitCount")).isEqualTo(1);
    }

    @Test
    void outOfOrderAndDuplicateCallbacksEndInTheCorrectTerminalState() {
        assertThat(facts(3).get("outcomes")).isEqualTo(List.of("APPLIED", "IGNORED_STALE", "DUPLICATE", "IGNORED_ILLEGAL"));
        assertThat(facts(3)).containsEntry("finalOrderStatus", "SUCCESS").containsEntry("taskStatus", "SUCCEEDED");
    }

    @Test
    void downChannelFailsOverAndTheSwitchIsLoggedThenTheCircuitSkipsIt() {
        assertThat(facts(4)).containsEntry("downChannel", "FakeBankD");
        assertThat(facts(4).get("switches")).isEqualTo(List.of(
            "FakeBankD->FakeBankC:SUBMIT_FAILED", "FakeBankD->FakeBankC:SUBMIT_FAILED", "FakeBankD->FakeBankC:SUBMIT_FAILED"));
        assertThat(num(4, "switchLogRows")).isEqualTo(3);
        assertThat(num(4, "downChannelSubmitCalls")).as("circuit opens after 3 consecutive failures").isEqualTo(3);
        assertThat(strings(4, "finalChannels")).containsExactly("FakeBankC");
        assertThat(num(4, "tasksSucceeded")).isEqualTo(4);
    }

    @Test
    void partialSuccessStopsTheTaskAsNotFinalizedWithAReason() {
        assertThat(facts(5)).containsEntry("taskStatus", "NOT_FINALIZED").containsEntry("reason", "partial: 2/3 succeeded");
        assertThat(facts(5).get("orderStatuses")).isEqualTo(List.of("FAILED", "SUCCESS", "SUCCESS"));
    }

    @Test
    void killedMidFlightRestartsCompletesAndNeverDebitsTwice() {
        assertThat(facts(6)).containsEntry("crashObserved", true).containsEntry("crashPoint", "AFTER_ACCEPT")
            .containsEntry("taskStatus", "SUCCEEDED");
        assertThat(num(6, "recoveryResolved")).isEqualTo(1);
        assertThat(num(6, "recoveryRequeued")).isZero();
        assertThat(num(6, "orderCount")).isEqualTo(3);
        assertThat(nums(6, "submitCallsPerOrder")).containsExactly(1L, 1L, 1L);
        assertThat(nums(6, "debitsPerOrder")).containsExactly(1L, 1L, 1L);
    }

    @Test
    void disagreeingStatementProducesAllFourDifferenceTypes() {
        assertThat(facts(7)).containsEntry("balancedBeforeTamper", true).containsEntry("balanced", false)
            .containsEntry("balancedAfterRestore", true);
        assertThat(strings(7, "diffTypes")).isEqualTo(Set.of("LONG", "SHORT", "AMOUNT_MISMATCH", "STATUS_MISMATCH"));
        Map<?, ?> byType = (Map<?, ?>) facts(7).get("diffCountByType");
        assertThat(byType.keySet()).containsExactlyInAnyOrder("LONG", "SHORT", "AMOUNT_MISMATCH", "STATUS_MISMATCH");
        assertThat(byType.values().stream().map(v -> ((Number) v).longValue())).containsOnly(1L);
        assertThat(num(7, "persistedDiffRows")).isEqualTo(4);
    }

    @Test
    void monitoringJsonIsPerChannelAndConsistentWithTheSimulatedLatency() throws IOException {
        JsonNode json = new ObjectMapper().readTree((String) facts(8).get("json"));
        assertThat(json.isObject()).isTrue();
        assertThat(strings(8, "channels")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(strings(8, "channels")).isEqualTo(iteratorToSet(json.fieldNames()));
        boolean sawReasons = false;
        for (String channel : strings(8, "channels")) {
            JsonNode stats = json.get(channel);
            assertThat(stats.get("attempts").asLong()).isPositive();
            assertThat(stats.get("successRate").asDouble()).isBetween(0.0, 1.0);
            assertThat(stats.get("p50Ms").asLong()).isPositive().isEqualTo(stats.get("p95Ms").asLong());
            sawReasons |= stats.get("failureReasons").size() > 0;
        }
        assertThat(sawReasons).isTrue();
        assertThat(facts(8)).containsEntry("anyFailureReasons", true);
        assertThat(json.has("FakeBankC") ? json.get("FakeBankC").get("p50Ms").asLong() : 100L)
            .as("FakeBankC latency is 100 ms in channels.yml").isEqualTo(100L);
    }

    private static Set<String> iteratorToSet(java.util.Iterator<String> it) {
        Set<String> out = new HashSet<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = DemoRunnerTest.class.getResourceAsStream(path)) {
            assertThat(in).as("classpath resource " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
