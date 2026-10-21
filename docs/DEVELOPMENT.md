# Development and verification

Use JDK 21, Android SDK platform 34 and build-tools 34.0.0. The wrapper pins Gradle 8.8; Android bytecode remains Java 17. Set `JAVA_HOME` and `ANDROID_HOME` before running the wrapper. Minimum supported Android is API 28; CI instrumentation targets API 28 and 35.

The current local toolchain is JDK `21.0.12.1+1`. CI selects Temurin 21; an identical vendor/patch and a clean-machine build have not yet been verified.

On this machine, local AVD definitions are kept in `build/avd`; set `ANDROID_AVD_HOME` to that absolute directory before launching the emulator. API 28 uses `IvyWalletApi28` on port 5572, and API 35 uses `IvyWallet` on port 5570. Run one at a time for connected tests, address ADB commands by serial, and preserve the AVD data when stopping the task-owned emulator.

Run from the repository root (Windows uses `gradlew.bat`):

```sh
python -m unittest discover -s scripts -p quality_tests.py -v
python scripts/quality.py ratchet
./gradlew testDebugUnitTest detekt verifyPaparazziDebug
./gradlew lintRelease
python scripts/quality.py lint build/reports/lint/lint.xml
./gradlew assembleDemo -PcomposeCompilerReports=true
./gradlew :ci-actions:compose-stability:run
./gradlew :shared:data:core:connectedDebugAndroidTest :shared:domain:connectedDebugAndroidTest
./gradlew assembleRelease -PivyUnsignedRelease=true
```

For a focused financial-safety iteration:

```sh
./gradlew :shared:base:testDebugUnitTest :shared:data:core:testDebugUnitTest :shared:ui:navigation:testDebugUnitTest :ci-actions:compose-stability:test
```

Finish integration before testing. Record the commit, command, exit code and reports together. If source changes after compilation starts, rerun the affected checks; do not accept mixed classes from an earlier source revision. A worker's commit or successful `git diff --check` does not establish Android correctness.

The ratchet compares individual baseline entries, suppression annotations and feature dependencies with immutable upstream commit `710733dbe4729a437d573cdfb8429c50e617ded1`. Missing reports and malformed reports fail validation. Do not regenerate baselines to accept a failing change.

## Application identity and signing

The fork uses `com.ivy.wallet.optimized`; debug/demo use `com.ivy.wallet.optimized.debug`. The earlier demo (`com.ivy.wallet.debug`) and original app retain their installations. Transfer data with a backup. Demo uses the repository's public development key and is not a production signing channel.

Release signing reads `IVY_SIGNING_STORE_FILE`, `IVY_SIGNING_STORE_PASSWORD`, `IVY_SIGNING_KEY_ALIAS` and `IVY_SIGNING_KEY_PASSWORD`. Keep the production key outside the checkout. `-PivyUnsignedRelease=true` produces the unsigned R8 candidate for checks. The manual promotion workflow checks a successful Quality run and its exact main SHA, verifies the candidate digest/package/debuggable flag, aligns and signs that artifact, then creates a draft release. Installation/upgrade verification of the signed artifact remains a release acceptance requirement.

## MCP execution

Workers use isolated Git worktrees, finite request/cost/time limits and exact configured model IDs. The primary checkout reviews and integrates their patches, resolves conflicts and runs the Android checks. Do not increase a failed session's lifetime budget or reset its accounting. Preserve partial changes and distinguish them from accepted results.

The Harness gateway applies a conservative request-body size limit as well as a token/output cap. A session can hit that boundary before its request or monetary allowance ends. Start a separate bounded task from an integrated checkpoint for new work; a failed continuation is not evidence that the application change passed.
