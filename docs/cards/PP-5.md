---
task: PP-5
allowed_paths:
  - pom.xml
  - src/main/*
max_files: 15
max_lines: 700
---
# PP-5 Daily reconciliation and channel monitoring

## Goal
Prove the local ledger agrees with what each channel says happened (reconciliation), and expose per-channel health (monitoring). Builds on PP-4 (the JobWorker already persists channel before submit; keep that behaviour). Red tests (src/test/java/com/example/payplat/payment/*) are architect-owned: do not edit them. If a test seems wrong, report instead of editing. Do not edit PP-1..PP-4 tests.

## Contract (package `com.example.payplat.payment`)

### Build
`pom.xml`: add `org.springframework.boot:spring-boot-starter-json` (Jackson; tests use `ObjectMapper`).

### Gateway
- `record StatementLine(long orderId, long amountCents, String status)`; `status` is the channel's raw word: `SUCCESS`, `FAILED` or `PROCESSING`.
- `ChannelGateway` gains `List<StatementLine> statement(String channelId, LocalDate date)` (abstract). The default bean must still fail fast: `statement` throws `UnsupportedOperationException("no channel gateway configured")` like `submit`/`query`.
- Test gateways implement `statement` themselves (architect updates `ScriptedGateway` and `CrashGateway`).

### Schema (append to `schema.sql`, idempotent)
- `recon_diff(id BIGINT auto-increment PK, recon_date DATE NOT NULL, type VARCHAR NOT NULL, channel_id VARCHAR, order_id BIGINT, local_amount BIGINT NULL, channel_amount BIGINT NULL, local_status VARCHAR NULL, channel_status VARCHAR NULL, created_at TIMESTAMP)` - no foreign key.
- `gateway_call_log(id BIGINT auto-increment PK, channel_id VARCHAR NOT NULL, order_id BIGINT NOT NULL, accepted BOOLEAN NOT NULL, reason VARCHAR NULL, duration_ms BIGINT NOT NULL, at TIMESTAMP)` - no foreign key.

### Reconciliation
- `enum ReconDiffType { LONG, SHORT, AMOUNT_MISMATCH, STATUS_MISMATCH }`.
- `record ReconDiff(ReconDiffType type, String channelId, long orderId, Long localAmount, Long channelAmount, String localStatus, String channelStatus)` - absent side = `null`.
- `record ReconReport(LocalDate date, List<ReconDiff> diffs, boolean balanced)`; `balanced` is true iff `diffs` is empty; `diffs` is never null and unmodifiable.
- `ReconciliationService` (bean): `ReconReport reconcile(LocalDate date)`.
  - For EVERY channel id known to `ChannelRegistry` (registry order) call `gateway.statement(channelId, date)` exactly once. A gateway exception propagates (fail fast): nothing is written and rows of earlier runs stay.
  - Local side of a channel = orders with that `channel_id` whose `updated_at`, converted to a UTC calendar date IN JAVA (`Timestamp.toInstant()` -> UTC date; do NOT use SQL `DATE()`/`CAST`, the JVM zone must not matter), equals `date`. Orders with null `channel_id` or a channel unknown to the registry are not part of any local side.
  - Compare by `orderId` within a channel:
    - `LONG`: statement line with no local order in that channel's local side. `localAmount`/`localStatus` null.
    - `SHORT`: local order in status `SUCCESS` with no statement line. `channelAmount`/`channelStatus` null. A local order in any other status without a line is NOT a diff.
    - both present, amounts differ -> `AMOUNT_MISMATCH` (both amounts filled, statuses filled).
    - both present, statuses not equivalent -> `STATUS_MISMATCH`. Equivalent pairs only: local SUCCESS~SUCCESS, FAILED~FAILED, SUBMITTED~PROCESSING, UNKNOWN~PROCESSING. Everything else (including a local CREATED order that has a line) differs. An order may yield both an AMOUNT_MISMATCH and a STATUS_MISMATCH.
  - `localStatus` = the `OrderStatus` name, `channelStatus` = the line's raw status.
  - `diffs` ordered by `channelId`, then `orderId`, then enum declaration order of the type.
  - Persistence: in one transaction delete existing `recon_diff` rows with this `recon_date`, then insert one row per diff (`created_at` from `Clock`). Re-running a date replaces its rows; other dates are untouched; a balanced run leaves zero rows for that date.

### Monitoring
- `JobWorker`: around every `gateway.submit` it measures the duration with the injected `Clock` (`clock.instant()` before and after, in whole milliseconds) and inserts one `gateway_call_log` row per submit attempt: `accepted` = the result's `isAccepted()`, `reason` = the rejection reason (null if accepted). If `submit` throws, a row with `accepted=false`, `reason = "EXCEPTION:" + exception simple class name` is inserted and the exception is re-thrown unchanged. Attempts skipped by the rate limiter / circuit breaker make no call and no row.
- `record ChannelStats(long attempts, long successes, double successRate, long p50Ms, long p95Ms, Map<String, Long> failureReasons)`.
- `MonitoringService` (bean):
  - `Map<String, ChannelStats> snapshot()` computed from `gateway_call_log`; only channels with at least one attempt appear; iteration order = channel id ascending. `successes` = accepted rows; `successRate = successes / attempts`; `failureReasons` = count of rows per `reason` among non-accepted rows (empty map if none; a null reason is counted under `"UNKNOWN"`).
  - Percentiles: nearest rank on the sorted durations, `rank = ceil(p/100 * n)`, value = element at index `rank-1`. (n=10: p50=5th, p95=10th; n=20: p50=10th, p95=19th.)
  - `String toJson()`: Jackson serialisation of the snapshot as `{"<channelId>":{"attempts":..,"successes":..,"successRate":..,"p50Ms":..,"p95Ms":..,"failureReasons":{"<reason>":n}}}`; an empty snapshot is `{}`.

## Not doing
No scheduler, REST endpoint, metrics registry, demo command (PP-6). No change to PP-4 recovery semantics. No edits to docs/ or src/test. Do not weaken tests.
