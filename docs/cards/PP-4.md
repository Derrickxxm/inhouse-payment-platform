---
task: PP-4
allowed_paths:
  - pom.xml
  - src/main/*
max_files: 15
max_lines: 700
---
# PP-4 Crash compensation (recovery by query-first, never double debit)

## Goal
After a process kill, orders and jobs left half-way must be repaired on restart WITHOUT a second debit. Recovery asks the channel first and only lets the normal worker re-submit when the channel proves it never saw the order. Red tests (src/test/java/com/example/payplat/payment/*) are architect-owned: do not edit them. If a test seems wrong, report instead of editing. Do not edit PP-1..PP-3 tests.

## Contract (package `com.example.payplat.payment`)

### Schema (append to `schema.sql`; must be idempotent against an EXISTING file database, so use `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`)
- `payment_order.submitting_at TIMESTAMP` - the "submitting" marker.
- `job.started_at TIMESTAMP` - set from `Clock` when a worker claims the job (PENDING->RUNNING).
- `recovery_event(id BIGINT auto-increment PK, order_id BIGINT NOT NULL, action VARCHAR NOT NULL, channel_answer VARCHAR, at TIMESTAMP)` - no foreign key. One row per candidate order handled by a `recover()` run; `action` in {REQUEUED, RESOLVED, UNTOUCHED}; `channel_answer` = the `ChannelQueryResult` name, or null when no query was made. Timestamps from `Clock`.

### JobWorker change: persist the intent BEFORE calling the channel
Inside the per-channel attempt, strictly before `gateway.submit(...)`, in its own committed statement: `UPDATE payment_order SET channel_id = <chosen>, submitting_at = <clock now>, attempts = attempts + 1, updated_at = <clock now>`. The order stays `CREATED` until the existing post-submit transaction. On failover each new channel overwrites `channel_id` before its own submit, so at any instant `channel_id` is the channel of the call in flight. Claiming a job also sets `job.started_at`. Behaviour and end states for PP-2/PP-3 tests are unchanged (the final NO_ELIGIBLE_CHANNEL update still nulls `channel_id`). A crash therefore leaves: job RUNNING, order CREATED, `channel_id` + `submitting_at` set (or `channel_id` null if the crash was before the marker).

### Recovery
- `record RecoveryReport(int requeued, int resolved, int untouched)`.
- `RecoveryService` (bean): `RecoveryReport recover()`. Callable directly any time. Not transactional as a whole: queries run outside any DB transaction.
- Window = property `payplat.recovery.window-seconds`, default 120 (declare in application.yml). "Older than the window" means strictly older than `now - window` by `Clock`.
- Candidates, processed in order-id order:
  1. Orders in `CREATED` whose job is `RUNNING` with `started_at` older than the window ("stuck in flight").
  2. Orders in `SUBMITTED` or `UNKNOWN` with `updated_at` older than the window.
  Everything else (fresh jobs, terminal orders, PENDING jobs) is not a candidate and must not be queried.
- For every candidate FIRST `gateway.query(order.channel_id, order.id)`, except a CREATED candidate with `channel_id` null: it provably never reached any channel, so no query is made (and `channel_answer` is null) and it is treated like NOT_FOUND below.
- Decision table (every `apply` uses source `"RECOVERY"`):
  - CREATED candidate: SUCCESS -> `apply(SUBMITTED)` then `apply(SUCCESS)`; FAILED -> `apply(SUBMITTED)` then `apply(FAILED)`; PROCESSING -> `apply(SUBMITTED)`; in all three the job becomes `DONE` and the report counts `resolved`. NOT_FOUND (or null channel) -> order stays CREATED, job `RUNNING` -> `PENDING` via conditional update (`WHERE status = 'RUNNING'`), counted `requeued`; `attempts` untouched.
  - SUBMITTED/UNKNOWN candidate: SUCCESS/FAILED -> `apply(SUCCESS|FAILED)`, `resolved`; PROCESSING -> `untouched`; NOT_FOUND on SUBMITTED -> `apply(UNKNOWN)`, `resolved`; NOT_FOUND on UNKNOWN -> `untouched`.
  - For every task with at least one APPLIED change call `TaskService.refresh(taskId)` once.
- `RecoveryService` NEVER calls `ChannelGateway.submit`. Re-submission only happens through the normal `JobWorker.runOnce()` after a requeue, so the worker's rules (and PP-2's persist-before-execute) stay the single path.
- Idempotent: a second `recover()` right after the first (same clock) returns `(0,0,0)`, makes no gateway call and writes no state_event / recovery_event rows.
- Each candidate writes exactly one `recovery_event` row.

### Startup hook
`ApplicationRunner` bean (class `RecoveryRunner`) calls `recover()` once at startup. Enabled by default; `payplat.recovery.on-startup=false` disables it entirely (bean absent or no-op). Tests that call `recover()` themselves disable it.

## Notes for the executor
- Crash tests use a file-based H2 database and two sequential Spring contexts in one JVM; the channel is simulated by a JVM-static "world" so it survives the context restart. Do not rely on in-memory state surviving.
- Schema DDL runs on every start against the same file: make it re-runnable.

## Not doing
No scheduler / periodic recovery (callers or a later card), no reconciliation or monitoring (PP-5), no FakeChannel simulator. No edits to docs/ or src/test. Do not weaken tests.
