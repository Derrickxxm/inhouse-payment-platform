package com.example.payplat.demo;

import com.example.payplat.PayplatApplication;
import com.example.payplat.demo.DemoReport.ScenarioResult;
import com.example.payplat.payment.*;
import com.example.payplat.routing.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DemoRunner implements ApplicationRunner {
    public DemoRunner() {}

    @Override public void run(ApplicationArguments args) throws Exception {
        if (!args.getNonOptionArgs().contains("demo")) return;
        DemoReport report = run(Files.createTempDirectory("payplat-demo-"));
        System.exit(report.allPassed() ? 0 : 1);
    }

    public DemoReport run(Path workDir) {
        String[] names = {"onboard-and-debit", "duplicate-submit", "callback-chaos", "channel-failover",
            "partial-success", "crash-recovery", "reconciliation-differences", "monitoring"};
        List<ScenarioResult> results = new ArrayList<>();
        for (int n = 1; n <= names.length; n++) {
            System.out.println("[" + n + "] " + names[n - 1]);
            Map<String, Object> facts = new LinkedHashMap<>();
            boolean passed = false;
            String detail;
            try {
                Path directory = workDir.resolve("scenario-" + n);
                Files.createDirectories(directory);
                // Refuse an old ledger rather than quietly reusing a previous demonstration.
                check(!Files.exists(directory.resolve("payplat.mv.db")), "database must be fresh: " + directory);
                if (n == 1) onboard(directory, facts);
                else if (n == 6) crash(directory, facts);
                else try (var context = context(directory, true)) {
                    var s = new Services(context);
                    switch (n) {
                        case 2 -> duplicate(s, facts);
                        case 3 -> chaos(s, facts);
                        case 4 -> failover(s, facts);
                        case 5 -> partial(s, facts);
                        case 7 -> reconciliation(s, facts);
                        case 8 -> monitoring(s, facts);
                        default -> throw new IllegalArgumentException("unknown scenario " + n);
                    }
                }
                passed = true;
                detail = "Verified " + names[n - 1] + " against service results and persisted state.";
                System.out.println("    facts: " + facts);
            } catch (Exception e) {
                detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            System.out.println("    " + detail);
            System.out.println(passed ? "    RESULT: PASS" : "    RESULT: FAIL " + detail);
            results.add(new ScenarioResult(n, names[n - 1], passed, detail, facts));
        }
        DemoReport report = new DemoReport(results);
        System.out.println("\n" + report.resultLine());
        return report;
    }

    private ConfigurableApplicationContext context(Path directory, boolean onboarded) {
        List<String> args = new ArrayList<>(List.of(
            "--spring.profiles.active=demo", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
            "--spring.datasource.url=jdbc:h2:file:" + directory.toAbsolutePath().resolve("payplat") + ";DB_CLOSE_DELAY=0",
            "--payplat.recovery.on-startup=false"));
        if (onboarded) args.add("--spring.config.additional-location=classpath:/demo-channels.yml");
        return new SpringApplicationBuilder(PayplatApplication.class).web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF).run(args.toArray(String[]::new));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
    private static void expect(Map<String, Object> facts, String key, Object expected) {
        check(expected.equals(facts.get(key)), key + " expected " + expected + ", observed " + facts.get(key));
    }
    private static void number(Map<String, Object> facts, String key, long expected) {
        check(facts.get(key) instanceof Number value && value.longValue() == expected,
            key + " expected " + expected + ", observed " + facts.get(key));
    }

    private void onboard(Path directory, Map<String, Object> f) throws Exception {
        Set<String> original;
        try (var context = context(directory, false)) {
            var s = new Services(context);
            original = new HashSet<>(s.registry.all().stream().map(ChannelProperties::id).toList());
            f.put("fastestBefore", s.fastest());
        }
        try (var context = context(directory, true)) {
            var s = new Services(context);
            String winner = s.fastest();
            f.put("fastestAfter", winner);
            check(s.registry.all().size() == original.size() + 1 && !original.contains(winner),
                "exactly one new YAML channel must win FASTEST");
            try (var input = DemoRunner.class.getResourceAsStream("/demo-channels.yml")) {
                String yaml = new String(java.util.Objects.requireNonNull(input).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                int start = yaml.indexOf("    - id: " + winner + "\n");
                check(start >= 0, "new channel block absent from YAML");
                yaml.substring(start).lines().forEach(line -> System.out.println("    " + line));
            }
            TaskView task = s.submit("onboard", 5000, Tier.FASTEST, 1);
            s.worker.runOnce();
            OrderView order = s.payment.get(task.taskId()).orders().getFirst();
            f.put("chosenChannel", order.channelId());
            f.put("tier", Tier.FASTEST.name());
            f.put("callbackOutcome", s.callback(order, "SUCCESS", 1));
            f.put("taskStatus", s.payment.get(task.taskId()).status().name());
            var recon = s.reconcile();
            f.put("reconBalanced", recon.balanced());
            f.put("reconDiffCount", recon.diffs().size());
            expect(f, "fastestBefore", "FakeBankC");
            expect(f, "chosenChannel", winner);
            expect(f, "callbackOutcome", "APPLIED");
            expect(f, "taskStatus", "SUCCEEDED");
            expect(f, "reconBalanced", true);
            number(f, "reconDiffCount", 0);
        }
    }

    private void duplicate(Services s, Map<String, Object> f) {
        TaskView first = s.submit("duplicate", 1000, Tier.FASTEST, 1);
        s.worker.runOnce();
        TaskView second = s.submit("duplicate", 1000, Tier.FASTEST, 1);
        s.worker.runOnce();
        OrderView order = s.payment.get(first.taskId()).orders().getFirst();
        f.put("taskIdFirst", first.taskId());
        f.put("taskIdSecond", second.taskId());
        f.put("taskCount", s.count("task"));
        f.put("gatewaySubmitCount", s.gateway.calls(order));
        f.put("debitCount", s.gateway.debits(order));
        expect(f, "taskIdSecond", first.taskId());
        number(f, "taskCount", 1);
        check(s.count("payment_order") == 1, "expected one order");
        number(f, "gatewaySubmitCount", 1);
        number(f, "debitCount", 1);
    }

    private void chaos(Services s, Map<String, Object> f) {
        var task = s.submit("chaos", 1000, Tier.FASTEST, 1);
        s.worker.runOnce();
        var order = s.payment.get(task.taskId()).orders().getFirst();
        check(order.status() == OrderStatus.SUBMITTED, "order must be SUBMITTED before callbacks");
        f.put("outcomes", List.of(s.callback(order, "SUCCESS", 2), s.callback(order, "PROCESSING", 1),
            s.callback(order, "SUCCESS", 2), s.callback(order, "FAILED", 3)));
        task = s.payment.get(task.taskId());
        f.put("finalOrderStatus", task.orders().getFirst().status().name());
        f.put("taskStatus", task.status().name());
        expect(f, "outcomes", List.of("APPLIED", "IGNORED_STALE", "DUPLICATE", "IGNORED_ILLEGAL"));
        expect(f, "finalOrderStatus", "SUCCESS");
        expect(f, "taskStatus", "SUCCEEDED");
    }

    private void failover(Services s, Map<String, Object> f) {
        String down = s.fastest();
        String next = s.router.rank(Tier.FASTEST).get(1);
        s.gateway.reject(down, "CHANNEL_DOWN");
        List<Long> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) tasks.add(s.submit("failover-" + i, 1000, Tier.FASTEST, 1).taskId());
        s.worker.runOnce();
        Set<String> channels = new HashSet<>();
        long succeeded = 0;
        for (long id : tasks) {
            var order = s.payment.get(id).orders().getFirst();
            channels.add(order.channelId());
            s.callback(order, "SUCCESS", 1);
            if (s.payment.get(id).status() == TaskStatus.SUCCEEDED) succeeded++;
        }
        var rows = s.context.getBean(SwitchLog.class).rows();
        List<String> switches = rows.stream().map(r -> r.from() + "->" + r.to() + ":" + r.reason()).toList();
        switches.forEach(row -> System.out.println("    switch: " + row));
        f.put("downChannel", down);
        f.put("downChannelSubmitCalls", s.gateway.calls(down));
        f.put("switchLogRows", rows.size());
        f.put("switches", switches);
        f.put("finalChannels", channels);
        f.put("tasksSucceeded", succeeded);
        number(f, "downChannelSubmitCalls", 3);
        number(f, "switchLogRows", 3);
        expect(f, "switches", java.util.Collections.nCopies(3, down + "->" + next + ":SUBMIT_FAILED"));
        expect(f, "finalChannels", Set.of(next));
        number(f, "tasksSucceeded", 4);
    }

    private void partial(Services s, Map<String, Object> f) {
        var task = s.submit("partial", 3000, Tier.FASTEST, 3);
        s.worker.runOnce();
        var orders = s.payment.get(task.taskId()).orders();
        for (int i = 0; i < orders.size(); i++) s.callback(orders.get(i), i < 2 ? "SUCCESS" : "FAILED", 1);
        task = s.payment.get(task.taskId());
        f.put("taskStatus", task.status().name());
        f.put("reason", task.reason());
        f.put("orderStatuses", task.orders().stream().map(o -> o.status().name()).sorted().toList());
        expect(f, "taskStatus", "NOT_FINALIZED");
        expect(f, "reason", "partial: 2/3 succeeded");
        expect(f, "orderStatuses", List.of("FAILED", "SUCCESS", "SUCCESS"));
    }

    private void crash(Path directory, Map<String, Object> f) {
        DemoGateway.Ledger truth;
        java.time.Instant restartAt;
        long taskId;
        boolean observed = false;
        try (var context = context(directory, true)) {
            var s = new Services(context);
            var task = s.submit("crash", 900, Tier.FASTEST, 3);
            taskId = task.taskId();
            s.gateway.crashAfterAccept(task.orders().getFirst().orderId());
            try { s.worker.runOnce(); }
            catch (DemoGateway.SimulatedCrash e) { observed = true; }
            truth = s.gateway.ledger();
            restartAt = s.clock.instant().plusSeconds(121);
        }
        // Only the independent bank ledger survives. All platform services read the file database anew.
        try (var context = context(directory, true)) {
            var s = new Services(context);
            s.gateway.restore(truth);
            s.clock.set(restartAt);
            var recovery = context.getBean(RecoveryService.class).recover();
            s.worker.runOnce();
            s.clock.advance(Duration.ofSeconds(61));
            context.getBean(StatusCorrector.class).runOnce();
            var task = s.payment.get(taskId);
            f.put("crashObserved", observed);
            f.put("crashPoint", observed ? "AFTER_ACCEPT" : "NONE");
            f.put("recoveryResolved", recovery.resolved());
            f.put("recoveryRequeued", recovery.requeued());
            f.put("orderCount", task.orders().size());
            f.put("submitCallsPerOrder", task.orders().stream().map(s.gateway::calls).toList());
            f.put("debitsPerOrder", task.orders().stream().map(s.gateway::debits).toList());
            f.put("taskStatus", task.status().name());
            expect(f, "crashObserved", true);
            number(f, "recoveryResolved", 1);
            number(f, "recoveryRequeued", 0);
            number(f, "orderCount", 3);
            expect(f, "submitCallsPerOrder", List.of(1, 1, 1));
            expect(f, "debitsPerOrder", List.of(1, 1, 1));
            expect(f, "taskStatus", "SUCCEEDED");
        }
    }

    private void reconciliation(Services s, Map<String, Object> f) {
        var task = s.submit("recon", 4000, Tier.FASTEST, 4);
        s.worker.runOnce();
        var orders = s.payment.get(task.taskId()).orders();
        orders.forEach(o -> s.callback(o, "SUCCESS", 1));
        f.put("balancedBeforeTamper", s.reconcile().balanced());
        s.gateway.tamper(orders.getFirst().channelId(), lines -> {
            List<StatementLine> changed = new ArrayList<>();
            for (var line : lines) {
                if (line.orderId() == orders.get(0).orderId()) continue;
                changed.add(new StatementLine(line.orderId(),
                    line.amountCents() + (line.orderId() == orders.get(1).orderId() ? 1 : 0),
                    line.orderId() == orders.get(2).orderId() ? "FAILED" : line.status()));
            }
            changed.add(new StatementLine(999999, 100, "SUCCESS"));
            return changed;
        });
        var report = s.reconcile();
        Map<String, Long> counts = new HashMap<>();
        report.diffs().forEach(d -> {
            counts.merge(d.type().name(), 1L, Long::sum);
            System.out.println("    difference: " + d);
        });
        f.put("balanced", report.balanced());
        f.put("diffTypes", Set.copyOf(counts.keySet()));
        f.put("diffCountByType", counts);
        f.put("persistedDiffRows", s.count("recon_diff"));
        s.gateway.restoreStatements();
        f.put("balancedAfterRestore", s.reconcile().balanced());
        expect(f, "balancedBeforeTamper", true);
        expect(f, "balanced", false);
        expect(f, "diffCountByType", Map.of("LONG", 1L, "SHORT", 1L, "AMOUNT_MISMATCH", 1L, "STATUS_MISMATCH", 1L));
        number(f, "persistedDiffRows", 4);
        expect(f, "balancedAfterRestore", true);
        check(s.count("recon_diff") == 0, "restored reconciliation must clear persisted differences");
    }

    private void monitoring(Services s, Map<String, Object> f) throws Exception {
        s.gateway.rejectEvery(s.fastest(), 4);
        for (int i = 0; i < 30; i++) s.submit("monitor-" + i, 1000, Tier.values()[i % Tier.values().length], 1);
        s.worker.runOnce();
        var monitoring = s.context.getBean(MonitoringService.class);
        String json = monitoring.toJson();
        System.out.println("    " + json);
        var tree = s.context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).readTree(json);
        Set<String> channels = new HashSet<>();
        Map<String, Long> p50 = new HashMap<>(), p95 = new HashMap<>();
        boolean reasons = false;
        var fields = tree.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String channel = field.getKey();
            var stats = field.getValue();
            channels.add(channel);
            p50.put(channel, stats.get("p50Ms").asLong());
            p95.put(channel, stats.get("p95Ms").asLong());
            reasons |= stats.get("failureReasons").size() > 0;
            long latency = s.registry.byId(channel).orElseThrow().latencyMs();
            check(stats.get("attempts").asLong() > 0 && stats.get("successRate").asDouble() >= 0
                && stats.get("successRate").asDouble() <= 1 && p50.get(channel) == latency
                && p95.get(channel) == latency, "monitoring disagrees with channel latency or counts: " + channel);
        }
        f.put("json", json);
        f.put("channels", channels);
        f.put("p50Ms", p50);
        f.put("p95Ms", p95);
        f.put("anyFailureReasons", reasons);
        check(channels.size() >= 3, "expected at least three monitored channels, observed " + channels);
        expect(f, "anyFailureReasons", true);
    }

    private static final class Services {
        final ConfigurableApplicationContext context;
        final PaymentService payment;
        final JobWorker worker;
        final DemoGateway gateway;
        final DemoClock clock;
        final Router router;
        final ChannelRegistry registry;
        Services(ConfigurableApplicationContext context) {
            this.context = context;
            payment = context.getBean(PaymentService.class);
            worker = context.getBean(JobWorker.class);
            gateway = context.getBean(DemoGateway.class);
            clock = context.getBean(DemoClock.class);
            router = context.getBean(Router.class);
            registry = context.getBean(ChannelRegistry.class);
        }
        TaskView submit(String id, long amount, Tier tier, int split) {
            return payment.submit(new DebitRequest(id, "synthetic-user", amount, tier, split));
        }
        String fastest() { return router.rank(Tier.FASTEST).getFirst(); }
        long count(String table) { return context.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
        String callback(OrderView order, String status, long sequence) {
            return context.getBean(CallbackService.class).onCallback(gateway.callback(order, status, sequence)).name();
        }
        ReconReport reconcile() { return context.getBean(ReconciliationService.class).reconcile(gateway.date()); }
    }
}
