# Cards

One card per feature: scope, allowed paths, budget. Red tests are written by the architect before the executor implements. Review records are in `docs/reviews/`.

- [PP-1](PP-1.md): skeleton, YAML channels, router and service tiers, channel switching, CI, public-content scan.
- [PP-2](PP-2.md): Task/Order persistence, idempotency, order split, partial success, persisted switch log.
- [PP-3](PP-3.md): order state machine, asynchronous callbacks (duplicate/out-of-order safe), status correction.
- [PP-4](PP-4.md): crash compensation, query-first recovery after restart, never a double debit.
- [PP-5](PP-5.md): daily reconciliation (LONG, SHORT, AMOUNT_MISMATCH, STATUS_MISMATCH) and per-channel monitoring.
- [PP-6](PP-6.md): one-command demo with eight scenarios, README and process records.
