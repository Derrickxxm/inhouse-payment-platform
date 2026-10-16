---
task: PP-2
allowed_paths:
  - pom.xml
  - src/main/*
max_files: 25
max_lines: 1200
---
# PP-2 Task/Order persistence, idempotency, split, partial success, persisted switch log

## Goal
Add a persistence layer and the payment core on top of the PP-1 routing code. Red tests (src/test/java/com/example/payplat/payment/*, plus src/test/resources/test-channels-maint.properties) are architect-owned: do not edit them. If a test seems wrong, report instead of editing. Do not edit PP-1 tests.

## Contract

### Build and persistence
- pom.xml: add `spring-boot-starter-jdbc` and `com.h2database:h2` (runtime). Use JdbcTemplate (no JPA, no Spring Data needed).
- `src/main/resources/application.yml`: in-memory H2 with a unique name per application context: `spring.datasource.url: jdbc:h2:mem:payplat-${random.uuid};DB_CLOSE_DELAY=-1`; `spring.sql.init.mode: always`; schema from `src/main/resources/schema.sql` (idempotent `CREATE TABLE IF NOT EXISTS`). Keep `spring.config.import` for channels.yml.
- Tables (names and columns are fixed, tests query them with JdbcTemplate):
  - `task(id BIGINT auto-increment PK, biz_request_id VARCHAR NOT NULL UNIQUE, user_id, amount_cents BIGINT, tier VARCHAR, status VARCHAR, reason VARCHAR NULL, created_at TIMESTAMP)`
  - `payment_order(id BIGINT auto-increment PK, task_id BIGINT NOT NULL, channel_id VARCHAR NULL, amount_cents BIGINT, status VARCHAR, attempts INT NOT NULL DEFAULT 0, reason VARCHAR NULL, updated_at TIMESTAMP)`
  - `job(id BIGINT auto-increment PK, order_id BIGINT NOT NULL, status VARCHAR NOT NULL, created_at TIMESTAMP)` with status PENDING -> RUNNING -> DONE
  - `channel_switch_log(id BIGINT auto-increment PK, task_id BIGINT NULL, order_id BIGINT NULL, from_channel VARCHAR, to_channel VARCHAR, reason VARCHAR, at TIMESTAMP)`
- Timestamps come from the injected `java.time.Clock`. Statuses are stored as the enum name.

### Package `com.example.payplat.payment`
- `enum TaskStatus { ACCEPTED, PROCESSING, SUCCEEDED, NOT_FINALIZED, FAILED }`; `enum OrderStatus { CREATED, SUBMITTED, SUCCESS, FAILED, UNKNOWN }`.
- `record DebitRequest(String bizRequestId, String userId, long amountCents, Tier tier, int splitInto)`. Compact constructor throws `IllegalArgumentException` when: bizRequestId or userId is null/blank, tier is null, amountCents <= 0, splitInto < 1 or > 5. A request with amountCents < splitInto is also rejected (an order amount must be >= 1).
- `record OrderView(long orderId, String channelId, long amountCents, OrderStatus status, int attempts, String reason)`.
- `record TaskView(long taskId, String bizRequestId, TaskStatus status, List<OrderView> orders, String reason)`; `orders` ordered by order id ascending.
- `PaymentService` (bean):
  - `TaskView submit(DebitRequest)`. Writes, in ONE transaction: the task (status ACCEPTED), `splitInto` orders (CREATED, channel_id null, attempts 0) and one job row (PENDING) per order. Amounts are split evenly (`amount / n` each), the remainder goes to the LAST order; the sum equals the request amount. submit never calls `ChannelGateway`.
  - Idempotent on `bizRequestId`: a repeated call (any other fields, even different) returns the existing task's current view and creates no task, order or job. Safe under concurrent calls: the UNIQUE constraint decides the winner, the loser catches the duplicate-key error and returns the winner's view. Exactly one task exists afterwards.
  - `TaskView get(long taskId)`; throws `NoSuchElementException` when absent.
- `interface ChannelGateway { SubmitResult submit(String channelId, long orderId, long amountCents); }` and `record SubmitResult(boolean accepted, String reason)` with static factories `accepted()` and `rejected(String reason)`. Main code provides a default `ChannelGateway` bean that throws `UnsupportedOperationException("no channel gateway configured")` on every call (fail fast; the FakeChannel simulator arrives in a later card). Tests register their own `@Primary` gateway.
- `JobWorker` (bean): `int runOnce()`. Claims PENDING jobs atomically (`UPDATE job SET status='RUNNING' WHERE id=? AND status='PENDING'`, claim succeeds only if 1 row updated), so two concurrent `runOnce` calls never process the same job; returns the number of jobs this call claimed. For each claimed job, in job-id order:
  1. Read the order and its task's tier. Do NOT hold a database transaction open around gateway calls (`TransactionSynchronizationManager.isActualTransactionActive()` must be false inside `ChannelGateway.submit`).
  2. Call `ChannelSwitchService.pickWithFailover(tier, taskId, orderId, predicate)` (new overload, see below); the predicate calls the gateway once per tried channel and increments the order's `attempts` by 1 per gateway call (persisted).
  3. On a pick: order -> SUBMITTED, `channel_id` set, job -> DONE. A job whose order is already SUBMITTED or beyond is never submitted again.
  4. On `NoEligibleChannelException`: order -> FAILED, `reason` = `NO_ELIGIBLE_CHANNEL`, job -> DONE.
  A channel is never called twice for the same order within a run, and never after the order reached SUBMITTED.
- `OrderStateService` (bean): `void apply(long orderId, OrderStatus newStatus)` sets the status and updated_at. It enforces nothing yet (PP-3 adds the state machine); `NoSuchElementException` for an unknown order. JobWorker uses it (or equivalent SQL) for its own transitions.
- `TaskService` (bean): `TaskView refresh(long taskId)` derives the task status from its orders and persists status and reason: all SUCCESS -> SUCCEEDED (reason null); all FAILED -> FAILED; at least one SUCCESS and at least one FAILED and every order is SUCCESS or FAILED -> NOT_FINALIZED with reason exactly `partial: x/y succeeded` (x = SUCCESS count, y = order count); anything else (any CREATED/SUBMITTED/UNKNOWN order) -> PROCESSING. A partial result is never reported as SUCCEEDED.

### Routing changes (package `com.example.payplat.routing`)
- `SwitchLog` stays a bean with the same API (`record(from,to,reason)`, `entries()` returning `List<Entry>` in insertion order as an immutable copy) but is now backed by `channel_switch_log` (JdbcTemplate); rows written by the 3-arg `record` have null task_id/order_id. Add `void record(Long taskId, Long orderId, String from, String to, String reason)` and `List<SwitchLog.Row> rows()` with `record Row(Long taskId, Long orderId, String from, String to, String reason)`, ordered by id. No in-memory list remains. All PP-1 tests keep passing.
- `ChannelSwitchService` gets an overload `String pickWithFailover(Tier tier, Long taskId, Long orderId, Predicate<String> trySubmit)` with identical semantics that logs switches with task/order ids; the existing 2-arg method delegates with nulls.

## Not doing
No REST controllers, FakeChannel simulator, callbacks, legal-transition enforcement, crash recovery, reconciliation, file-mode H2 (PP-3+). No edits to docs/ or src/test. Do not weaken tests.
