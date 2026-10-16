# PP-4 + PP-5 review (architect)

- Head reviewed: `d980205`
- Scope: executor changed pom.xml and src/main only. Implemented together because both cards' tests share ChannelGateway/JobWorker and must compile together.
- Tests: `./mvnw test` 169 passed (PP-1..PP-5).
- Architect test fixes found while running the build (tests only, no implementation change):
  1. `CrashApp.CrashConfig` was annotated `@Configuration`, so component scan in every other Spring test context picked it up and registered a second `testClock` bean (159 errors). Changed to `@TestConfiguration`.
  2. Two monitoring tests asked for 10/20 orders in one task, violating the PP-2 rule `splitInto <= 5`; they now submit several 5-way tasks.
- Design check: recovery queries the channel before anything else; it only re-queues a job (never calls submit) when the channel reports NOT_FOUND or the order never reached a channel; SUBMITTED + NOT_FOUND becomes UNKNOWN, not a re-submit. Crash tests on a file database across restarts show at most one accepted debit per order.
- P1 findings: 0. Gate decision: ALLOW.
