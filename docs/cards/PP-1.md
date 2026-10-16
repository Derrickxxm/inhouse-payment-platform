---
task: PP-1
allowed_paths:
  - pom.xml
  - mvnw
  - mvnw.cmd
  - .mvn/*
  - src/main/*
  - .github/workflows/ci.yml
  - scripts/public_scan.py
  - .gitignore
max_files: 25
max_lines: 1200
---
# PP-1 skeleton, YAML channels, router and tiers, switching, CI, public scan

## Goal
A bootable Spring Boot 3 / Java 21 / Maven-wrapper app whose channel catalogue lives only in YAML and whose Router ranks channels per tier with rate limiting, circuit breaking and failover. Plus CI and a public-content scan. Red tests (src/test/java/com/example/payplat/routing/*, tests_py/*) already exist and are architect-owned: do not edit them. Test resources under src/test/resources are also architect-owned.

## Contract
Build: Java 21, Spring Boot 3.x parent, `spring-boot-starter`, `spring-boot-starter-test` (test scope), JUnit 5, Maven wrapper committed (`./mvnw -B test` works). No DB in this card.

Packages: `com.example.payplat.PayplatApplication` (@SpringBootApplication, @ConfigurationPropertiesScan). Everything else lives in `com.example.payplat.routing`.

- `src/main/resources/application.yml` imports `channels.yml` (`spring.config.import: classpath:channels.yml`). `channels.yml` defines `payplat.channels`: a list with 4+ synthetic channels (FakeBankA/B/C, FakeWallet; plausible values).
- `record ChannelProperties(String id, int feeBps, int latencyMs, double stability, double successRate, int rateLimitPerSec, boolean maintenance)`; list bound under prefix `payplat` property `channels` (relaxed binding; Java-property keys like `payplat.channels[0].feeBps` must work). Adding a channel is config only.
- `ChannelRegistry` (bean): `List<ChannelProperties> all()` (config order), `Optional<ChannelProperties> byId(String id)`.
- `enum Tier { CHEAPEST, FASTEST, MOST_RELIABLE, BALANCED }`.
- A `java.time.Clock` bean (UTC system clock) in main code; all time use goes through the injected Clock. Tests override it with a @Primary bean.
- `CircuitBreakerRegistry` (bean): `void recordFailure(String id)`, `void recordSuccess(String id)`, `boolean isOpen(String id)`. Per channel. Consecutive-failure counter; success resets it to 0. The 3rd consecutive failure opens the circuit at clock-now. `isOpen` is true while now < openedAt + 30s, false from exactly 30s on (half-open: channel is eligible for a trial). A failure recorded while half-open re-opens the circuit at the new now; a success closes it.
- `RateLimiterRegistry` (bean): `boolean tryAcquire(String id)` consumes one permit in the current second window (window = clock.instant() epoch second) and returns false when `rateLimitPerSec` permits are already used in that window; `boolean isExhausted(String id)` is non-consuming (true iff tryAcquire would return false). A new second starts a fresh window.
- `Router` (bean): `List<String> rank(Tier tier)` returns eligible channel ids best-first. Eligible = not maintenance AND not `isExhausted` AND not circuit open. rank never consumes permits. Scores (ties always broken by id ascending):
  - CHEAPEST: feeBps ascending.
  - FASTEST: latencyMs ascending.
  - MOST_RELIABLE: successRate*stability descending.
  - BALANCED: sum of four min-max-normalized components, descending. Normalization is computed over ALL configured channels (including ineligible ones), so eligibility changes never reorder the rest. fee and latency components are (max-x)/(max-min); stability and successRate components are (x-min)/(max-min); if max==min the component is 0 for all.
- `SwitchLog` (bean, in-memory, thread-safe): `void record(String from, String to, String reason)`, `List<SwitchLog.Entry> entries()` (insertion order, immutable copy), `record Entry(String from, String to, String reason)`. (Persistence arrives in PP-2.)
- `NoEligibleChannelException extends RuntimeException`.
- `ChannelSwitchService` (bean): `String pickWithFailover(Tier tier, Predicate<String> trySubmit)`. Take `router.rank(tier)` once; for each candidate in order: skip it (reason RATE_LIMITED) if `tryAcquire` is false, else call `trySubmit`; true -> `recordSuccess`, return the id; false -> `recordFailure` (reason SUBMIT_FAILED). Whenever a candidate is abandoned and a later candidate is then tried, record `SwitchLog.record(abandonedId, nextTriedId, reasonOfAbandonment)`. If none succeeds throw NoEligibleChannelException (also when rank is empty). No entry is logged for the final abandoned candidate.

scripts/public_scan.py (stdlib only): `python scripts/public_scan.py [ROOT]` (ROOT defaults to cwd). Scans text files listed by `git ls-files` in ROOT (skip files that are not valid UTF-8; ignore .git and target). Exit 1 and print `path:line: kind` for each finding, exit 0 if clean. Findings: IPv4 address (four octets 0-255); email whose domain is not example.com/example.org/example.net, not ending in .example or .invalid, and not users.noreply.github.com; any deny-list term, case-insensitive. The deny-list must NOT appear in plain text anywhere in the repo (the scan scans itself): embed it in the script as a base64 string of a comma-separated UTF-8 list and decode at runtime. The string is:
`RmludXAs5Yeh5pmuLOa/gOWFieS7mCxMYXNlclBheSxtZXJjZWRlc2JlbnoscHVodWks5a6d5LuYLOaYk+WunSzpgJrogZQs5a+M5Y+LLOmTtuiBlCzlhYjplIs=`

.github/workflows/ci.yml: on push and pull_request; ubuntu-latest; JDK 21 (temurin, maven cache); steps: `./mvnw -B test` and `python3 scripts/public_scan.py`.

.gitignore: target/, data/, IDE files.

## Not doing
No database, entities, REST controllers, FakeChannel simulator, or persistence of SwitchLog (PP-2+). No edits to docs/ or tests. Do not weaken tests; if a test seems wrong, report instead of editing.
