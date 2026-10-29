# Development and verification

Use Eclipse Temurin from `.java-version` (currently `21.0.12.1+1`), the Android platform selected by `compile-sdk` in `gradle/libs.versions.toml` (currently 35), and build-tools 34.0.0. The wrapper pins Gradle 8.11.1 with its distribution checksum; Android bytecode remains Java 17. Set `JAVA_HOME` and `ANDROID_HOME` before running the wrapper. Minimum supported Android is API 28; CI instrumentation targets API 28 and 35.

Run the environment preflight below before building. It checks the vendor, exact patch/build, actual Java executable and SDK metadata, and removes a stale success file on failure. Gradle uses `JAVA_HOME`; implicit JDK discovery/download and the configuration cache are disabled. The platform API is fixed; its installed revision/JAR hash and the unpinned platform-tools are recorded. Primary CI workflows upload this environment record with their reports.

`python scripts/verify_environment.py --sdk-packages` lists the required platform and build-tools without an installed JDK or SDK. CI uses `.github/actions/setup-project-android` with Python 3.12 and the same package list. It supports both the native Android CLI (`sdk install`, slash-separated package IDs) and legacy `sdkmanager` (semicolon-separated IDs). A successful installer exit still requires the environment preflight to verify the installed files.

CI uses `.github/actions/setup-pinned-java` to pass the raw pin to setup-java v6.1.0 at an immutable SHA. Its version-file parser discards the fourth version component, so the local action passes `java-version` directly. The upstream [Temurin resolver](https://github.com/actions/setup-java/blob/b843ebf9b913146b38da2eaa7aeddf76074ae1ad/src/distributions/temurin/installer.ts) handles the exact OpenJDK patch/build. Local parser/Bash checks do not establish remote CI success or a pristine machine build. `Quality` requires Python/ratchet, Android quality, both integration APIs and all five CI-actions JVM suites through reusable workflows.

On this machine, local AVD definitions are kept in `build/avd`; set `ANDROID_AVD_HOME` to that absolute directory before launching the emulator. API 28 uses `IvyWalletApi28` on port 5572, and API 35 uses `IvyWallet` on port 5570. Run one at a time for connected tests, address ADB commands by serial, and preserve the AVD data when stopping the task-owned emulator.

Compatibility pins are intentionally retained from the AI fork: Kotlin 2.0.21 with KSP 2.0.21-1.0.27 plus Compose 1.7.6/Material3 1.3.1; Paparazzi stays at 1.3.4 because 1.3.5 breaks cross-module fonts on Windows, and Slack Compose lint 1.4.2 is required for the current K2/lint stack.

Snapshot changes need visual review with recorded source and old/new hashes -- never threshold increases or baseline regeneration to hide failures (for example, `docs/quality/screenshot-review-2026-10-29.json`); the GitHub API 28/35 emulator workflow settles KVM udev events before checking read/write access.

Run from the repository root (Windows uses `gradlew.bat`):

```sh
python scripts/verify_environment.py --out build/environment.json
python -m unittest discover -s scripts -p environment_tests.py -v
python -m unittest discover -s scripts -p quality_tests.py -v
python -m unittest discover -s scripts -p '*audit*tests.py' -v
python scripts/quality.py ratchet
./gradlew testDebugUnitTest detekt verifyPaparazziDebug
./gradlew :ci-actions:base:test :ci-actions:issue-assign:test :ci-actions:issue-create-comment:test :ci-actions:compose-stability:test :ci-actions:pr-description-check:test
./gradlew lintRelease
python scripts/quality.py lint build/reports/lint/lint.xml
./gradlew :app:assembleDemo :ci-actions:compose-stability:run -PivyComposeFullReports=true '-Pkotlin.incremental=false' --no-configuration-cache
./gradlew :shared:data:core:connectedDebugAndroidTest :shared:domain:connectedDebugAndroidTest
./gradlew assembleRelease -PivyUnsignedRelease=true
```

For a clean-checkout cohort, start with no project `build` or `.gradle` directories and record the commit and environment before compilation. Disable task-output and configuration cache reuse with `--no-build-cache --no-configuration-cache`; the recorded Windows cohort also used `--max-workers=1` and `-Pkotlin.incremental=false`. Global dependency/script cache reuse is a separate fact and must be reported. Keep the checkout unchanged until the cohort completes. Later CI-only commits must retain their own source identity and verification.

For a focused financial-safety iteration:

```sh
./gradlew :shared:base:testDebugUnitTest :shared:data:core:testDebugUnitTest :shared:ui:navigation:testDebugUnitTest :ci-actions:compose-stability:test
```

Finish integration before testing. Record the commit, command, exit code and reports together. If source changes after compilation starts, rerun the affected checks; do not accept mixed classes from an earlier source revision. A worker's commit or successful `git diff --check` does not establish Android correctness.

The ratchet compares individual baseline entries, suppression annotations and feature dependencies with immutable upstream commit `710733dbe4729a437d573cdfb8429c50e617ded1`. Missing reports and malformed reports fail validation. Do not regenerate baselines to accept a failing change.

## Application identity and signing

The fork preserves the existing AIvy Wallet identity `com.pavel.wallet`; debug/demo use `com.pavel.wallet.debug`. Updating an existing installation also requires its signing key. Earlier optimized demos (`com.ivy.wallet.optimized.debug`), the original app and `com.ivy.wallet.debug` retain separate installations; transfer their data with a backup. Demo uses the repository's public development key and is not a production signing channel. Historical evidence below retains the package IDs and toolchains used for those artifacts.

Release signing reads `IVY_SIGNING_STORE_FILE`, `IVY_SIGNING_STORE_PASSWORD`, `IVY_SIGNING_KEY_ALIAS` and `IVY_SIGNING_KEY_PASSWORD`. Keep the production key outside the checkout. `-PivyUnsignedRelease=true` produces the unsigned R8 candidate for checks. The manual promotion workflow checks a successful Quality run and its exact main SHA, verifies the candidate digest/package/debuggable flag, aligns and signs that artifact, then creates a draft release. Installation/upgrade verification of the signed artifact remains a release acceptance requirement.

The APK workflow retains its Actions artifact upload, with inherited Telegram delivery removed. Release-version PRs, stale handling and Gradle-wrapper upgrades require manual dispatch; publishing the fork does not enable their inherited schedules.

## MCP execution

Workers use isolated Git worktrees, finite request/cost/time limits and exact configured model IDs. The primary checkout reviews and integrates their patches, resolves conflicts and runs the Android checks. Do not increase a failed session's lifetime budget or reset its accounting. Preserve partial changes and distinguish them from accepted results.

The Harness gateway applies a conservative request-body size limit as well as a token/output cap. A session can hit that boundary before its request or monetary allowance ends. Start a separate bounded task from an integrated checkpoint for new work; a failed continuation is not evidence that the application change passed.

The loan-write slice is verified with `:temp:legacy-code:testDebugUnitTest`, `:feature:loans:verifyPaparazziDebug`, the shared UI/core suites, Detekt and the data-core connected tests on each API. New creator tests must use the required legacy `LoanRecord.loanRecordType`; worker patches are compiled and corrected in the primary checkout before acceptance. Failed or truncated Muse reviews are retained in the session ledger and do not count as completed reviews.

## Full Compose reports

Use the full-mode command above in one Gradle invocation. Both properties are required. Kotlin can restore its global incremental setting after task configuration, so the producer checks the effective flag before writing a completion marker. In PowerShell, quote the dotted property:

```powershell
.\gradlew.bat :app:assembleDemo :ci-actions:compose-stability:run -PivyComposeFullReports=true '-Pkotlin.incremental=false' --no-configuration-cache
```

Full mode writes to each module's `build/compose_full`, forces a nonincremental production compilation and bypasses task/build-cache reuse for those compiler tasks. App uses demo; libraries and widgets use release. Ordinary incremental reports stay in `compose_compiler` and cannot satisfy the gate. Compiler completion markers contain source/report hashes and one build identity. The fingerprint helper is shared between buildSrc and the checker; generated outputs and report archives are excluded.

The run task derives its scope from actual Kotlin Compose plugins and requires the shared report convention for every such project. The checker compares that graph with discovered declarations, including widget/Paparazzi conventions. Plugin lookups and references in strings are not declarations. Missing pairs/markers, changed sources, modified reports, wrong variants and mixed build identities fail; stale green summaries are removed first. Empty modules require the same full-compilation proof. Coverage is recorded in `ivy-compose-coverage.tsv` and uploaded alongside reports in CI.

The skippability check applies to restartable functions without skipping. Value-returning/inline helpers remain in coverage and are not false regressions; a skippable-but-nonrestartable row is invalid. See [Compose lifecycle rules](https://developer.android.com/develop/ui/compose/lifecycle#skipping) and [Kotlin 2.0.20 compiler metrics](https://github.com/JetBrains/kotlin/blob/v2.0.20/plugins/compose/design/compiler-metrics.md). Do not add stability annotations or regenerate a baseline merely to make valid helpers pass.

Source `5835c771b` verified all 38 projects/969 functions, 26 analyzer tests, Detekt and R8. Three real fault controls and unchanged-input compiler execution passed their expected outcomes. Saved evidence is independently checkable with `python outputs/compose-full-5835c771b/verify.py`. That checkpoint established fresh report coverage; it did not establish frame times, a clean checkout or remote CI success.

The 2026-10-27 cohort starts with an empty project checkout at `1d5a53cea`: 553 JVM/screenshot cases, 39 Python cases, lint, Detekt, full Compose and R8 passed. The original domain instrumentation case failed live-CDN DNS while all 106 Room cases passed. At `8640efc05`, its remote boundary uses fixed rates while real Room/repository/use-case logic checks exact saved values and repeat-sync idempotence. All 107 instrumentation cases passed on each API 28/35; fresh Compose and R8 checks also passed at that source. The later test/CI phase reused project outputs and is explicitly distinguished from the initial empty-checkout phase. Run `python outputs/clean-checkout-1d5a53cea/verify.py` to recheck the saved evidence without Gradle, SDK, emulator or network. Remote CI and physical frame budgets remain unverified.

## Offline tag-link maintenance

Use Python 3.12 and a SQLite snapshot of schema 131. The tool uses the standard library. It reads a consistent transaction, including uncheckpointed WAL records, and classifies tag-to-transaction references. Other tables contribute to a full preservation fingerprint. The report contains UUIDs, reasons and counts; names, notes and amounts are kept out of the report.

```sh
python scripts/audit_database.py audit snapshot.db --report audit.json
python scripts/audit_database.py repair-copy snapshot.db --review audit.json --backup before-repair.db --output repaired.db
```

Audit exits with 0 for no findings, 1 for findings, and 2 when it cannot complete. Review the JSON before running `repair-copy`. The reviewed report must still match the complete schema and all rows. A stale or altered report fails before creating a backup. Unsupported schemas, malformed UUIDs/flags and missing required columns also fail.

Repair creates an exclusive, verified pre-repair SQLite backup and performs the reviewed deletes in one transaction in a temporary copy. Only active links with a physically absent tag or transaction are eligible. Valid links, soft-deleted associations and links to tombstoned owners/tags remain intact. The complete resulting state must equal the reviewed state with exactly those eligible rows removed. Trigger failures or collateral changes prevent publication. The source is opened read-only throughout.

Backup, repaired copy and report paths must be new, with existing parent directories. Existing SQLite sidecars also prevent reuse of a destination. Output publication uses a same-volume hard link, requiring a filesystem that supports it, such as NTFS or ext4. A publication failure leaves the verified backup available. The outputs are SQLite files for developer maintenance; recovery UI integration remains roadmap work.

The schema-131 synthetic acceptance exported the repaired copy through the existing API-28 application, then imported the resulting Ivy Backup as an identical merge. Schema and every row matched after import, and the original AVD database was restored exactly. This covers physical orphan links; reviewed application recovery remains roadmap work.

## Active backup and explicit deletion

Export and merge normalization share `BackupActiveRecords.activeRecords()`. An association to a tag or transaction explicitly marked deleted in that payload is excluded from the active archive. The source database is never changed by this filter. Links whose owners are physically absent or ambiguous remain validation errors; a destination tombstone alone is insufficient proof. Conflicting live/deleted copies of one identity fail before writes.

`omittedDeletedTagAssociations` is optional diagnostic metadata with a default of zero. It records active associations omitted because of explicit owner deletion, survives repeated normalization and appears in the import warning. It must be nonnegative and cannot bypass reference validation. Old archives retain their existing decoding path. Backups contain the active wallet; they do not preserve raw tombstone history. Keep raw snapshots for reviewed maintenance and recovery.

The source `83dfa84a2` regression cohort contains 183 core JVM cases and 106 connected Room cases on each API 28/35. The new Room cases restore a complete active backup into empty Room, preserve manual rates/preferences, repeat import without duplication and verify rollback for unknown references. Its development R8 APK also passed a real populated update/export/reimport smoke check; evidence is in `outputs/backup-tombstones-83dfa84a2/`.
