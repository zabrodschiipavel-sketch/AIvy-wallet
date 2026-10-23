# Maintained fork roadmap

Approved direction: offline financial records, optional online/manual exchange rates, no upstream Firebase/polls, redesign Home/editor/navigation after data safety. Preserve existing functions and old backup import. First distribution: independently signed APK plus source.

## Current status

| Stage | Status | Completion gate |
|---|---|---|
| 0 — trustworthy baseline and delivery | In progress | Clean checkout builds; unit/screenshot/Android/lint/Detekt/Compose results recorded; negative controls fail |
| 1 — data integrity | In progress | Atomic restore/compound writes, safe account lifecycle, truthful import, safe ZIP, failure-injection and populated migration tests |
| 2 — financial core | Planned | Decimal money, explicit missing FX, stable recurrence instances, versioned backup contracts used by active features |
| 3 — lifecycle/navigation | In progress, intermediate implementation only | Per-entry ownership, AndroidX navigation, restored routes/drafts, latest-query cancellation and duplicate-save protection |
| 4 — key screens and performance | Planned | Home/editor/navigation UX, accessibility, reproducible physical-device performance budgets |
| 5 — retire legacy and maintained releases | Planned | Feature migrations remove old implementations; no temp dependencies; validated upgrades and repeatable release |

An in-progress checkpoint is not a release candidate. A worker declaring success does not close a stage. Record commands, exit codes, test counts, failures and artifact identity after integration.

## Execution order

1. P0-CI: finish fail-closed report validation, debt ratchet, shared quality and explicit artifact promotion workflows; pin local/CI build commands.
2. P1-RESTORE: integrate pure merge planner, Room transaction/snapshot boundaries, durable preferences recovery, manual FX, isolated/limited ZIP extraction and regression tests.
3. P1-ACCOUNTS: validate currency/history under one transaction, archive instead of deleting referenced accounts, preserve balance participation, filter new-operation selectors, retain history and allow unarchive.
4. P1-PAYMENTS: fix bulk skip/settle, recurring generation identity, atomic rule/loan/transaction commands, cancellation and double-tap behavior.
5. P1-LIFECYCLE: verify intermediate per-entry stores and startup recovery; then move routes to AndroidX, saved arguments and drafts.
6. P2-MONEY: BigDecimal DecimalAmount, canonical decimal storage, exact aggregates outside SQLite REAL/SUM; HALF_EVEN and DECIMAL128 division. Backfill from BigDecimal.valueOf(Double), retain old values until reconciliation.
7. P3-SLICES: editor→Home first, then accounts/categories, history/search/reports, planned/loans/budgets, settings/import/onboarding/widgets. Immutable StateFlow state, Room source of truth, no new legacy/direct DAO feature consumers.
8. P4-UX/PERF: Home/operations/accounts/more navigation, focused editor, shared components, accessibility and release-like Macrobenchmark/Perfetto. Measure on fixed 2K/20K fixtures; physical-device gates remain open until observed.
9. P5-DELIVERY: remove upstream services/unused permissions/body logging, compatible dependency upgrades, external signing, verify update/backup recovery, publish the tested artifact explicitly.

## Next bounded tasks

Next tasks (still open):

| Task | Risk and reproducer | Acceptance and code to retire |
|---|---|---|
| Complete fresh Compose reports | A local incremental build produced a header-only legacy production report despite compiled UI functions. Other report rows can still make the existing gate pass | Generate complete reports for the tested candidate and verify coverage from clean inputs; retire reliance on partial incremental snapshots. Preserve deliberately incomplete-report failures |
| Tombstone associations and application recovery | An active tag link can reference an existing tombstoned tag/transaction; backup normalization drops tombstones. The offline audit reports this ambiguity and preserves the link | Define and test history-preserving backup/recovery semantics, including empty-target restore. Replace the manual copy handoff with reviewed application recovery; retire any blanket-purge proposal |

Completed for offline physical-orphan maintenance on 2026-10-23 at `32cd52eb3`: read-only schema-131 audit and reviewed repair into a new SQLite copy. The original database stays unchanged; a verified pre-repair backup precedes an atomic, narrowly selected deletion. Twenty new stdlib tests and 13 existing quality tests passed. A real Room snapshot with two synthetic missing-owner/tag links retained its valid association and every other row/schema entry. API 28 exported it to Ivy Backup and reimported it as an identical merge without duplicates; the original AVD database was restored exactly. CI now includes the new tests. Tombstone policy, empty-target application restore and recovery UI remain open. Actual damage to user data has not been established; stage 1 remains in progress.

Completed for loan/record forms on 2026-10-21 at `27858aa88`: immutable original loan/record/parent snapshots are checked inside the owning Room transaction. The stale screen-account calculation was removed. Date selection no longer resets the other draft fields. All 254 focused JVM cases (including two screenshot comparisons), root/app Detekt and 104 Room cases on both API 28 and 35 passed. Three new Room cases cover a concurrent account change, record edits and a single winner between simultaneous saves. The R8 APK passed an exact populated update and real form conflict/date checks. Conflict recovery still uses the existing error UI and requires reopening the details screen to load current data; durable command identity and process-death drafts remain open. This does not close stage 1.

Completed on 2026-10-21 at `f97ea3eb1`: confirmed account-modal save. All callers await persistence, failures retain the draft with an error, and duplicate taps/stale completions are guarded; superseded create/edit account events were removed. Fourteen new regression tests passed, alongside the affected snapshots and analyzers. API 28 UI checks covered onboarding and account-history editors, failed currency changes, corrected retry and a populated demo update with exact financial-row comparison. See the implementation status for artifact identity and limits. Process-death drafts and stable command identity remain open; this does not close stage 1.

Completed on 2026-10-21 at `ecdadeeda`: ordinary transaction/tag cleanup. All four physical deletion entrypoints remove their matching tag links in one Room transaction; paid rule history, tag definitions and non-selected rows remain intact. The old orphan-link failure was reproduced before integration. All 77 Room tests passed on API 28 and 35, including 13 new cases; all 168 core JVM tests and Detekt passed. The updated R8 demo passed the Compose gate and a one-account API 28 update smoke check with exact row comparison. Existing orphan repair remains open; stage 1 is still in progress.

Completed for the loan-screen scope on 2026-10-21 at `0e3ac27cf`: atomic create/edit, confirmed modal results, draft retention and duplicate-save guards. All 92 Room tests passed on API 28 and 35, including 15 new cases; all 252 focused JVM tests, six snapshot comparisons and Detekt passed. The R8 demo was updated on the populated synthetic AVD with exact prior-data comparison; a real failing linked insert retained the draft and rolled back, then retry/create/edit preserved identities and refreshed Home. The Compose gate passed, with an observed incremental report-coverage gap that remains a stage-0 task. Reverse transaction-editor writes, stale loan edits and durable command identity remain open; this does not close stage 1.

## Data contracts

- Backup merge: UUID identity, identical rows skipped, conflicts stop before writes; names and notes are never globally rewritten. Full replacement is a separate operation with a prior backup.
- DB and external preferences: committed Room journal, durable preference commit, replay before app initialization; no partial success hidden as zero imported.
- Missing FX is an explicit incomplete valuation. Historic recorded amounts remain fixed; manual overrides take priority and survive backups.
- Referenced accounts retain history. Changing their currency is rejected; create another account and transfer explicit amounts instead.
- Recurrence instance identity includes rule and scheduled date; executed/skipped history survives regeneration.
- Populated migration fixtures start at schema 106. Older unknown databases require a separate recovery fixture; no destructive fallback.

## Acceptance evidence

Financial fixtures cover income/expense, both transfer ends/currencies, excluded/archived accounts, loans, tiny crypto/large values and absent rates. Restore checks exact IDs, references, amounts, settings, repeated import, corrupted archive, conflicts and interruption. Android checks process recreation, IME/Back/lock, notifications/widgets and release R8. Performance targets (cold start <=2s, p95 frameOverrun <=0 at 60Hz) are targets until verified on a named physical device.

See `IMPLEMENTATION_STATUS.md` for the current execution checkpoint and outstanding checks.
