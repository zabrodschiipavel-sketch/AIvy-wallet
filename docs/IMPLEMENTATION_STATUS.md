# Implementation status — 2026-10-20

Base upstream commit: `710733dbe4729a437d573cdfb8429c50e617ded1`.

Previous optimization: signed demo APK and emulator checks are documented in the local `outputs/PERFORMANCE.md` and `outputs/verification.json`; overall jank improvement was not established. Physical-device checks remain pending.

Stages 0 and 1 remain in progress. The current checkpoint is not a release candidate and does not complete the roadmap.

Integrated work includes atomic backup merge/export snapshots, UUID conflict and tombstone protection, durable preference recovery, bounded ZIP/raw input, repository cache invalidation, account history guards and schema 131, per-navigation-entry ViewModel ownership, and restored root settings refresh. Firebase runtime/plugins and poll/home/Play Review prompts have been removed; online exchange-rate retrieval remains. Full AndroidX navigation and the financial decimal migration remain open.

Observed verification checkpoints:

| Source checkpoint | Check | Observed result |
|---|---|---|
| `23371b88c` | base / data core / navigation / Compose-validator JVM suites | 50 / 157 / 6 / 6 tests passed, respectively |
| `69f38faf9` | data core JVM suite | 164 tests passed, no failures or errors; newer tombstone and cancellation cases included |
| `69f38faf9` | Python quality validator suite | 13 tests passed, including missing/malformed reports and deliberately broken quality inputs |
| `69f38faf9` | debt ratchet | Passed; no new baseline entries or suppressions; feature legacy/DAO references 651 → 648 |
| `150ad8d7f` core sources | data core JVM suite | 167 tests passed, no failures or errors |
| `150ad8d7f` core sources | API 35 Room instrumentation | 49 of 51 tests passed; UTF-16 export and schema-119 migration failed |
| `55fb6fc58` | app Detekt | Passed; root Detekt stopped before analysis on a Gradle input/dependency validation error |
| `46f5aeea7` | API 35 Room instrumentation | 54 tests passed, zero failures/errors/skips; all 25 migration paths, both schema-119 variants and Unicode backup roundtrip |
| `46f5aeea7` | Python quality suite / debt ratchet | 13 tests passed; ratchet passed without new exceptions or feature legacy/DAO edges |
| `46f5aeea7` | core / CSV / settings / root JVM suites | 167 / 5 / 2 / 6 tests passed, respectively |
| `46f5aeea7` | planned-payment API 35 instrumentation | All 8 tests passed, including real rollback after earlier payment/rule writes |
| `a5dfc41aa` | planned-payment JVM suite | All 9 tests passed after correcting the static suspend mock's callback argument |
| `dafc4be86` | combined app/planned JVM + root/app Detekt | Passed; exit 0, 704 tasks (3 executed / 701 up-to-date), no new analyzer exceptions |
| `dafc4be86` | unsigned release with R8 | `:app:assembleRelease` completed; APK package/debuggable validation passed; remaining lint/screenshot invocation has not finished |

The combined Gradle command at `23371b88c` still exited with failure because app Detekt reported an overlong startup method; passing component tests do not make that command green. Subsequent extraction and formatting fixed the app findings. The first Android run uncovered a second pre-existing schema/export mismatch: schema 119 already contains `transactions.loanId`. The migration guard and streaming UTF-16 writer now pass the Android run. Detekt uses individual Kotlin-file inputs to avoid false task dependencies on Paparazzi output directories. App and root now apply the same existing analyzer convention and frozen baseline; the combined invocation passed.

Core source files were unchanged while the `150ad8d7f` instrumentation/JVM checks ran. Independently integrated legacy-payment and CSV changes were outside that component's dependency graph. Preserve the first failure report under `build/mcp-roadmap/checkpoint-150ad8d7f/`; it is not a green acceptance result.

Logs and generated reports are local ignored artifacts under `build/mcp-roadmap/` and each module's `build/test-results/`. Relevant logs include `safety-integration.log`, `legacy-unit-and-io.log`, and `common-analyzer-check.log`. A fresh clean checkout build and API 28/physical-device tests remain unverified. Release lint and the existing screenshot suite are still running in `release-lint-screenshots.log`; the combined command is not accepted as green yet.

The R8 APK and its mapping are preserved in `build/mcp-roadmap/checkpoint-dafc4be86/`. The unsigned APK is 11,921,604 bytes, package `com.ivy.wallet.optimized`, non-debuggable, SHA-256 `2a8331d3e3700d872a0f16295ab99844986970031b9cad8766481dcd00ea54d7`. A separate smoke-test copy is signed with the repository's public development key, not a production key; installation has not yet been checked. The API 28 system image and a separate `IvyWalletApi28` AVD are prepared, without changing the existing AVD's data.

Integrated data-safety changes:

- Base-currency writes freeze inherited account currencies in one transaction; the settings screen refreshes accounts and publishes the committed value in sequence. Failed or cancelled writes do not leave a separate currency memo. Focused repository, settings-viewmodel and API 35 tests passed.
- Account creation/editing and its balance-adjustment transaction use a common Room boundary and publish cache invalidation after it. Four real-Room success/failure tests passed in the first Android run; modal completion and duplicate-submit handling remain open.
- Bulk planned-payment APIs select pending records, retain settlement metadata, re-read existing rows and include one-time-rule deletion in the same Room transaction. All nine focused JVM tests and all eight real-Room tests passed. No production behavior or assertions were weakened to fix the earlier mock recursion.
- CSV counts confirmed saves and reports original failed source lines. All five focused unit tests passed. CSV parsing can still create accounts/categories before a transaction fails; this change does not provide whole-import rollback or retry idempotency.

Deterministic UTF-16LE backup fixtures are generated by `scripts/generate_performance_fixture.py`. Local outputs contain 2,000 and 20,000 transactions with SHA-256 `49f2502fbc943ce6d29ba25a19db148db6d062f4ed652babb84b8e85c74b0e71` and `0f8704eca5963c9a3d440f0daea5c79fadc34b8a5cb4ff324f874d59e0b2a302`. Their import and physical-device performance have not yet been measured.

The archive-control UI patch `e91fa22f6` is not accepted: it leaves the modal open without an asynchronous save result. Account-and-initial-balance atomicity is verified; confirmed dismissal and duplicate-submit protection must be completed before that interface is integrated. Existing user installations and the earlier demo APK are preserved.

The MCP audit at `dafc4be86` confirmed separate loan/record/financial writes in `LoanDetailsViewModel`: record creation refreshes before its financial transaction, edits save linked amounts separately, and deletion can leave only part of the graph removed. Atomic deletion (`dd2db5b17`, review fixes `28fe2a51d`) and seven real-Room tests (`da37ab403`) are prepared in isolated worktrees, not yet integrated or run. Compose report coverage for the app (`36902624b`, syntax fix `a3bb88d0f`) is also awaiting integration and execution. Loan/record create and edit commands remain open.

MCP workers must work from a committed checkpoint in isolated worktrees, make bounded changes in their owned areas, avoid modifying user databases/AVDs, and report exact tests/results. Integration and acceptance are performed in the primary checkout.
