---
task: PP-3
allowed_paths:
  - pom.xml
  - src/main/*
max_files: 20
max_lines: 900
---
# PP-3 Order state machine, asynchronous callbacks, status correction

## Goal
Make the order state machine real, accept channel callbacks that may be duplicated, reordered or forged, and add a periodic status corrector that fixes local state to the channel's truth. Red tests (src/test/java/com/example/payplat/payment/*) are architect-owned: do not edit them. If a test seems wrong, report instead of editing. Do not edit PP-1/PP-2 tests.

## Contract (package `com.example.payplat.payment` unless stated)

### Schema (append to `src/main/resources/schema.sql`, idempotent)
- `state_event(id BIGINT auto-increment PK, order_id BIGINT NOT NULL, from_status VARCHAR, to_status VARCHAR, outcome VARCHAR NOT NULL, source VARCHAR NOT NULL, at TIMESTAMP)` - no foreign key.
- `callback_log(id BIGINT auto-increment PK, channel_id VARCHAR, order_id BIGINT, status VARCHAR, sequence BIGINT, sent_at TIMESTAMP, outcome VARCHAR NOT NULL, received_at TIMESTAMP)` - no foreign key; `status` stores the raw status string received.
- Timestamps come from the injected `Clock`.

### OrderStateService
- `enum ApplyResult { APPLIED, DUPLICATE, IGNORED_ILLEGAL }`.
- `ApplyResult apply(long orderId, OrderStatus newStatus, String source)` and `ApplyResult apply(long orderId, OrderStatus newStatus)` (the latter = source `"DIRECT"`).
- Legal transitions only: CREATED->SUBMITTED; SUBMITTED->SUCCESS|FAILED|UNKNOWN; UNKNOWN->SUCCESS|FAILED. SUCCESS and FAILED are terminal and immutable.
  - legal -> status and `updated_at` updated, `APPLIED`.
  - `newStatus` equals the current status -> `DUPLICATE`; nothing changes (`updated_at` untouched).
  - anything else -> `IGNORED_ILLEGAL`; nothing changes.
- Never throws for these three outcomes. Unknown order id -> `NoSuchElementException` (as in PP-2).
- Every call writes exactly one `state_event` row: `from_status` = status before, `to_status` = requested status, `outcome` = the enum name, `source`, `at` = clock now. The status change and its event are written atomically; the read-check-update must be safe against concurrent callers (conditional `UPDATE ... WHERE id=? AND status=?`, retry the decision if 0 rows).
- JobWorker keeps working: its own CREATED->SUBMITTED transition may keep using raw SQL or go through `apply` with source `"WORKER"`; either way the end state is unchanged. Its CREATED->FAILED (`NO_ELIGIBLE_CHANNEL`) outcome is a worker-internal terminal failure and must keep working exactly as in PP-2 (raw SQL is fine; do not route it through the state machine).

### Callbacks
- `record ChannelCallback(String channelId, long orderId, String status, long sequence, Instant sentAt)`.
- `enum CallbackOutcome { APPLIED, DUPLICATE, IGNORED_ILLEGAL, IGNORED_STALE, NOOP }`.
- `CallbackService` (bean): `CallbackOutcome onCallback(ChannelCallback)`. Never throws for bad input; every call writes exactly one `callback_log` row with the outcome. Evaluated in this order:
  1. Unknown order id, null/blank/unrecognised `status` string, or `channelId` not equal to the order's `channel_id` (including an order whose channel_id is null) -> `IGNORED_ILLEGAL`, no state change.
  2. Let `last` = highest `sequence` among earlier `callback_log` rows of this order with outcome APPLIED, DUPLICATE or NOOP. `sequence < last` -> `IGNORED_STALE`; `sequence == last` -> `DUPLICATE` (a redelivery); no state change in both cases.
  3. Status mapping (case-sensitive): `SUCCESS`->SUCCESS, `FAILED`->FAILED, `UNKNOWN`->UNKNOWN, `PROCESSING` -> `NOOP` (no state change, no `state_event`).
  4. Otherwise `OrderStateService.apply(orderId, mapped, "CALLBACK")`: APPLIED->`APPLIED`, DUPLICATE->`DUPLICATE`, IGNORED_ILLEGAL->`IGNORED_ILLEGAL`.
  5. Only when the result is `APPLIED`, call `TaskService.refresh(taskId)` for the order's task.

### Status correction
- `enum ChannelQueryResult { SUCCESS, FAILED, PROCESSING, NOT_FOUND }`.
- `ChannelGateway` gains `ChannelQueryResult query(String channelId, long orderId)` (abstract). The default gateway bean must still fail fast: convert the PP-2 lambda into a class (or anonymous class) whose `submit` AND `query` throw `UnsupportedOperationException("no channel gateway configured")`.
- `StatusCorrector` (bean): `int runOnce()`; returns the number of orders it queried.
  - Candidates: orders with status SUBMITTED or UNKNOWN and `updated_at` strictly older than `now - window`, by `Clock`. Window = property `payplat.corrector.window-seconds`, default 60 (declare in application.yml). Orders in any other status are never queried.
  - For each candidate, call `gateway.query(order.channel_id, order.id)` (outside any DB transaction), then:
    - SUCCESS / FAILED -> `apply(orderId, SUCCESS|FAILED, "QUERY")`
    - PROCESSING -> no change
    - NOT_FOUND: SUBMITTED order -> `apply(orderId, UNKNOWN, "QUERY")`; UNKNOWN order -> no change (no `apply`, no event)
  - For every order whose `apply` returned APPLIED, call `TaskService.refresh(taskId)` for its task (once per task per run is enough).
  - The corrector NEVER calls `ChannelGateway.submit` (re-submission is PP-4's job and only after a query).
  - No scheduling annotation is required in this card (callers invoke `runOnce()`); do not add `@EnableScheduling`.

## Known interactions with PP-2 tests (architect-owned, adjusted by the architect, not by you)
PP-2 tests used `OrderStateService.apply` as an unrestricted setter. Under the state machine these became illegal:
- `IdempotencyTest.repeatedSubmitReflectsCurrentTaskStatus`: CREATED->SUCCESS directly.
- `TaskRefreshTest.taskWith(...)` (used by several tests): CREATED->SUCCESS/FAILED/SUBMITTED directly (CREATED->UNKNOWN too).
- `TaskRefreshTest.neverReportsPartialAsSuccessAcrossAllStatusCombinations`: cycles through every status including leaving terminal states; cannot be expressed through legal transitions.
The architect rewrites them to use new test helpers `drive(orderId, status)` (legal path via CREATED->SUBMITTED first) and `force(orderId, status)` (direct SQL, for the exhaustive test only). The adjusted PP-2 tests pass against the current PP-2 main code and must still pass after your change.

## Not doing
No scheduler, REST controller, FakeChannel simulator, crash recovery, reconciliation, file-mode H2 (PP-4+). No edits to docs/ or src/test. Do not weaken tests.
