# PP-2 review (architect)

- Head reviewed: `081a9cf`
- Scope: executor changed only pom.xml and src/main (allowed_paths); no tests/docs touched.
- Tests: `./mvnw test` 79 passed (PP-1 + PP-2); public scan PASS.
- Design check: task/orders/jobs written in one transaction before any channel call; jobs claimed with a conditional UPDATE (no double processing by concurrent workers); channel calls happen outside DB transactions.
- Known gap, by design deferred to PP-4: if the process dies after the channel accepted but before the order is marked SUBMITTED, the order stays CREATED with its job RUNNING; PP-4 recovery must query the channel before any re-submit.
- P1 findings: 0. Gate decision: ALLOW.
