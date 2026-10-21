# Implementation status — 2026-10-21

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
| `dafc4be86` | unsigned release with R8 / release lint | Assembly and package/debuggable validation passed; lint execution report passed Error/Fatal validation |
| `8e8b8f439` | API 28 Room instrumentation | 61 tests passed, zero failures/errors/skips, including seven atomic loan/record deletion cases |
| `8e8b8f439` | core / navigation / planned-payment JVM suites | 167 / 6 / 9 tests passed; Compose-validator suite: 9 passed |
| `8e8b8f439` | accounts screenshots | All 4 snapshots passed with the existing images after the preview-host fix |
| `ab9fa012c` | real Compose compiler reports | Validator passed, zero new unstable composables; removed UI plugins from the empty data-testing module |
| `73d7cdddc` | tagged-loan regression on API 28 | One selected test reproduced orphan tag associations after deletion; expected failure preserved before the fix |
| `8184bb97f` | API 28 Room / core JVM | 64 / 167 tests passed, zero failures or errors; combined invocation still failed on UUID test-helper formatting |
| `ae6b5243a` | API 35 Room + root/app Detekt | 64 tests passed, zero failures/errors/skips; both analyzers passed, combined exit 0 |
| `fcc11f83c` | full screenshot / R8 / release lint invocation | Exit 0; 2,436 tasks, 747 executed / 1,689 up-to-date; 432 tests in 64 fresh XML reports passed, including 38 screenshot tests in 13 classes; zero failures/errors/skips |
| `fcc11f83c` | fresh lint XML / debt ratchet | Report validated with no Error/Fatal; no new baseline entries, suppressions or feature legacy/DAO edges |
| `fcc11f83c` | Compose gate after release compilation | Passed, zero new unstable composables; all declared UI modules and generated variants checked |
| `f97ea3eb1` | account-save JVM / affected screenshots / root and app Detekt | Combined exit 0; 78 passing tests in eight reports, including 14 new regressions and four verified feature snapshots; UI-core tests reused valid unchanged outputs; no image baseline changes |
| `f97ea3eb1` | R8 demo / Compose gate | Combined exit 0; 1,397 tasks, 161 executed / 1,236 up-to-date; zero new unstable composables |
| `f97ea3eb1` | API 28 populated demo update and account-editor smoke | Existing financial UUIDs and amounts retained; invalid currency save kept the editor/draft, corrected retry closed it and refreshed history; one account and one initial-balance transaction remained; integrity check passed and crash buffer empty |

The combined Gradle command at `23371b88c` still exited with failure because app Detekt reported an overlong startup method; passing component tests do not make that command green. Subsequent extraction and formatting fixed the app findings. The first Android run uncovered a second pre-existing schema/export mismatch: schema 119 already contains `transactions.loanId`. The migration guard and streaming UTF-16 writer now pass the Android run. Detekt uses individual Kotlin-file inputs to avoid false task dependencies on Paparazzi output directories. App and root now apply the same existing analyzer convention and frozen baseline; the combined invocation passed.

Core source files were unchanged while the `150ad8d7f` instrumentation/JVM checks ran. Independently integrated legacy-payment and CSV changes were outside that component's dependency graph. Preserve the first failure report under `build/mcp-roadmap/checkpoint-150ad8d7f/`; it is not a green acceptance result.

Logs and generated reports are local ignored artifacts under `build/mcp-roadmap/` and each module's `build/test-results/`. The first release/lint/screenshot sweep exposed a shared preview crash and was stopped after preserving the failed report. `IvyPreview` and `IvyWalletPreview` now provide a disposable, private preview ViewModel owner; the production navigation guard remains strict. The full screenshot sweep subsequently passed at `fcc11f83c`, using the existing images. The combined command and report index are preserved in `checkpoint-fcc11f83c/qa.json`; full log: `current-release-screenshots.log`. A fresh clean checkout build and physical-device tests remain unverified.

The R8 APK and its mapping are preserved in `build/mcp-roadmap/checkpoint-dafc4be86/`. The unsigned APK is 11,921,604 bytes, package `com.ivy.wallet.optimized`, non-debuggable, SHA-256 `2a8331d3e3700d872a0f16295ab99844986970031b9cad8766481dcd00ea54d7`. A separate copy signed with the repository's public development key installed and opened offline on the isolated API 28 AVD without a crash. The agreement screen rendered; onboarding and financial UI scenarios were not exercised. This binary predates the loan/preview changes. It is neither a current-branch release nor a performance benchmark. Existing AVD and application data were preserved.

The newer R8 artifact at `fcc11f83c` is preserved separately in `build/mcp-roadmap/checkpoint-fcc11f83c/`: unsigned APK 11,938,128 bytes, SHA-256 `387cd4b2a56d1018fa03f75cf742320e5aa5671b496ee111e72960f2cc29ab2a`, package/debuggable validation passed. `candidate.json` records the exact source commit; `smoke-artifact.json` records the mapping and development-signed copy hashes. Signature verification and installation over the older test build on the isolated API 28 AVD succeeded. Offline launch rendered the agreement screen, with a live foreground activity and an empty crash buffer. First-install time and the initial agreement state survived this update; populated financial UI, production signing and upgrade from a real release are not covered by this smoke check. The AVD was stopped with its data retained. This is not a physical-device performance measurement.

Integrated data-safety changes:

- Base-currency writes freeze inherited account currencies in one transaction; the settings screen refreshes accounts and publishes the committed value in sequence. Failed or cancelled writes do not leave a separate currency memo. Focused repository, settings-viewmodel and API 35 tests passed.
- Account creation/editing and its balance-adjustment transaction use a common Room boundary and publish cache invalidation after it. Four real-Room success/failure tests passed in the first Android run. All account-modal callers now await persistence; write failures retain the draft with an error, repeated taps are guarded, and disposed/replaced modal completions cannot dismiss the current editor. Nine controller tests and five AccountCreator tests passed. A committed write followed by a refresh failure returns an explicit warning result and does not offer another write as a retry. Onboarding suggestions use the same guard and retain confirmed additions even if refreshing fails.
- Bulk planned-payment APIs select pending records, retain settlement metadata, re-read existing rows and include one-time-rule deletion in the same Room transaction. All nine focused JVM tests and all eight real-Room tests passed. No production behavior or assertions were weakened to fix the earlier mock recursion.
- CSV counts confirmed saves and reports original failed source lines. All five focused unit tests passed. CSV parsing can still create accounts/categories before a transaction fails; this change does not provide whole-import rollback or retry idempotency.

Deterministic UTF-16LE backup fixtures are generated by `scripts/generate_performance_fixture.py`. Local outputs contain 2,000 and 20,000 transactions with SHA-256 `49f2502fbc943ce6d29ba25a19db148db6d062f4ed652babb84b8e85c74b0e71` and `0f8704eca5963c9a3d440f0daea5c79fadc34b8a5cb4ff324f874d59e0b2a302`. Their import and physical-device performance have not yet been measured.

The archive-control UI patch `e91fa22f6` is not integrated. Its asynchronous account-save prerequisite is now implemented, but the patch must be adapted to the confirmed-result API and independently verified before acceptance. Existing user installations and the earlier demo APK are preserved.

The account-save checkpoint removes the fire-and-forget create/edit account events from the affected screens. Modal state and quick-add operations use owned child jobs; closing/replacing the editor invalidates the old completion. Cancellation at the database commit boundary still needs stable command identity/reconciliation, and process-death draft restoration remains a stage-3 task. This change does not provide general command idempotency or complete the navigation migration.

Evidence for this slice is under `build/mcp-roadmap/account-save/`. The first integration attempt exposed analyzer findings; the next attempt built the APK but still failed on six analyzer findings and an exception-identity assertion. The latter crossed an IO coroutine boundary, where stack-trace recovery may copy the exception; the test now checks the propagated error message and that refresh never ran. The original failed XML/log are retained in `checkpoint-035d00385/`. Scoped layout helpers and formatting fixed the analyzer findings without adding suppressions. `acceptance.log` and `qa.json` record the successful tests/Detekt/snapshot invocation (5m19s); `final-apk.log` records the successful R8/Compose invocation (8m35s). Full release lint was last checked at `fcc11f83c`; this slice ran the demo build's vital lint checks.

The current development-signed, non-debuggable R8 demo is preserved in `checkpoint-f97ea3eb1/`: package `com.ivy.wallet.optimized.debug`, 11,939,764 bytes, SHA-256 `5012b87dd8a59af0533ba3ae318f88450043392c1c519b41ce6beacefab3f1b6`. `artifact.json` identifies the full source commit and mapping hash. Offline API 28 checks first exercised onboarding on the `035d00385` demo, then updated that isolated installation to the final APK and repeated failure/retry through Accounts → Transactions → Edit account. UI XML/screenshots and `ui-smoke.json` retain the observations; read-only copies of the synthetic database confirm the same single account and initial-balance transaction, including exact UUIDs, after the update and retry. First-install time survived and the crash buffer was empty. The AVD was stopped with its data retained. This does not validate production signing, an upgrade from the upstream app, API 35 UI behavior or physical-device performance.

Four bounded Muse MCP sessions supported implementation and review; three completed and one test-writing attempt stopped at its token limit without changes. All workers are stopped, no uncertain requests remain, and Harness reports a combined estimated cost of USD 0.005439278788. Prohibited GPT-6 SOL / GPT-6.1 SOL routes were not called or resumed. Local verification, rather than worker completion, determined acceptance; `mcp-sessions.json` retains the accounting and limited review results.

Atomic loan/record deletion and its seven original Room tests are integrated. The command removes tombstones and transactions linked through either loan or record IDs, refreshes caches outside the Room lock, and only publishes success after confirmed commit. The UI guards duplicate deletion and retains the screen on failure. Loan/record create and edit commands remain open.

Further review found that physical financial-row deletion left tag associations behind, which conflicts with backup reference validation. The API 28 regression was reproduced at `73d7cdddc`. Fix `8184bb97f` selects the same complete transaction set and deletes its associations in batches of 500 inside the existing transaction. Tests also compare tag definitions/unrelated links, inject failures after tag deletion, and exceed the API 28 SQL binding limit. All 64 Room tests passed on both API 28 and 35; core JVM tests passed (167). After wrapping the UUID helper, the API 35/Detekt invocation passed. Evidence: `loan-tags-red.log`, `loan-tags-green.log`, `loan-tags-api35.log` and the preserved XML under `checkpoint-73d7cdddc/` and `checkpoint-8184bb97f/`. A bounded Muse MCP review found no defect in the patch scope; acceptance rests on the local checks. Ordinary transaction-deletion paths still need the same association-integrity review.

Compose report discovery now covers the app and declared UI modules, including inline plugin declarations. Missing or malformed required reports remain fatal. The combined check at `8e8b8f439` failed on test formatting and the empty data-testing module's missing report; the latter had no source files and unnecessarily applied the entire feature/Compose convention. `ab9fa012c` uses the data module convention and the real-report gate passed without weakening report validation. No analyzer baseline was expanded.

MCP workers must work from a committed checkpoint in isolated worktrees, make bounded changes in their owned areas, avoid modifying user databases/AVDs, and report exact tests/results. Integration and acceptance are performed in the primary checkout.
