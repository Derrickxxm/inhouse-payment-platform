# inhouse-payment-platform — design (architect)

Synthetic, clean-room demo of a company-internal payment platform: "keep the hard parts in the platform, keep it simple for business lines". All channels, amounts, users are generated. Built with the multi-agent delivery playbook (card → red tests → executor → review → CI → gate → Owner sign-off on exact commit).

## Stack
Java 21, Spring Boot 3.x, Maven wrapper, H2 (file mode under `./data` for the crash demo, in-memory for most tests), Spring Data JDBC or JPA (executor's choice, keep it simple), JUnit 5. No message broker: async work = persistent `job` table + in-process worker. CI: GitHub Actions ubuntu-latest, JDK 21.

Package root: `com.example.payplat`.

## Domain
- **Channel** (YAML only, `src/main/resources/channels.yml`, list `payplat.channels`): `id`, `feeBps` (int), `latencyMs` (int, typical settlement time), `stability` (0..1), `successRate` (0..1), `rateLimitPerSec` (int), `maintenance` (bool). Adding a channel = adding a YAML block; no code change.
- **Tier** (enum): `CHEAPEST`, `FASTEST`, `MOST_RELIABLE`, `BALANCED`. `Router.rank(Tier)` returns eligible channels ordered by the tier's score; eligibility excludes `maintenance`, channels whose limiter is full, and channels whose circuit is open (≥3 consecutive failures, half-open after 30 s, clock injectable). BALANCED = equal-weight normalized score of the four dimensions.
- **Channel switching**: when the chosen channel is ineligible or a submit fails, try the next channel of the same tier; every switch is persisted in `channel_switch_log(task_id, order_id, from_channel, to_channel, reason, at)`.
- **FakeChannel** simulator (per channel, seeded, deterministic in tests): `submit(order) -> ACCEPTED | REJECTED(reason)`; produces callbacks that can be duplicated, reordered, delayed; `query(orderId) -> SUCCESS | FAILED | PROCESSING | NOT_FOUND`; `statement(date) -> List<StatementLine(orderId, amountCents, status)>`. Failure injection is configurable for tests/demo.
- **Task** (business request): `id`, `bizRequestId` (unique), `userId`, `amountCents`, `tier`, `status` ∈ {`ACCEPTED`, `PROCESSING`, `SUCCEEDED`, `NOT_FINALIZED`, `FAILED`}, `reason`.
- **Order** (one debit attempt slice): `id`, `taskId`, `channelId`, `amountCents`, `status` ∈ {`CREATED`, `SUBMITTED`, `SUCCESS`, `FAILED`, `UNKNOWN`}, `attempts`, `updatedAt`.
- Task completion: all orders SUCCESS → `SUCCEEDED`; all FAILED → `FAILED`; mix of SUCCESS and FAILED with no pending → `NOT_FINALIZED` with reason `partial: x/y succeeded` (never reported as success).

## Rules (the "proof, not pretty results" discipline)
1. Persist before execute: Task + its first orders + their jobs are written in one transaction before any channel call.
2. Idempotency: same `bizRequestId` returns the existing task; never a second debit (channel submit count per order ≤ 1 unless the previous attempt is known FAILED).
3. Order state machine accepts only legal transitions: CREATED→SUBMITTED; SUBMITTED→SUCCESS|FAILED|UNKNOWN; UNKNOWN→SUCCESS|FAILED. Terminal states are immutable; duplicate/out-of-order callbacks are recorded and ignored.
4. Crash compensation: on startup and periodically, non-terminal orders older than the window are recovered by **querying the channel first**; re-submit only if the channel says NOT_FOUND. No double debit.
5. Status correction: periodic query of SUBMITTED/UNKNOWN orders fixes local state to the channel's truth.
6. Daily reconciliation: local ledger vs channel statement → report lines of type `LONG` (channel has it, local doesn't), `SHORT` (local SUCCESS, channel missing), `AMOUNT_MISMATCH`, `STATUS_MISMATCH`; balanced day = empty report.
7. Monitoring: per channel success rate, latency p50/p95, failure reason counts, exposed as JSON via a service method (and printed by the demo).

## Cards
PP-1 skeleton, YAML channels, router & tiers, switching, CI, public scan.
PP-2 Task/Order persistence, idempotency, split, partial success.
PP-3 callbacks state machine, status correction.
PP-4 crash compensation (file H2, restart).
PP-5 reconciliation & monitoring.
PP-6 demo command, README (Why / How this was built), process records.
