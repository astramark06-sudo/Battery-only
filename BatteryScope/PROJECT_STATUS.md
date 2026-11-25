# BatteryScope project status and engineering handoff

## 2026-11-25 — Cumulative CI repair and verification gate

- Batch 7 Android run 45661523315: build/unit/lint/permission/inventory steps succeeded; instrumentation failed during JUnit class initialization because `largeOlderHistoryDoesNotChangeRecentOverview` inferred Int return from Log.i instead of JVM void. Independently checked build ZIP 15045422140 SHA-256 a32ccdeff5425aa9a4c6314115b15e4ad18a036610389ec6fe2bee8b1f22ea98, 103 JVM passed, 64 lint warnings/0 errors, APK 1,059,312 bytes SHA-256 1248695c02d472c148af6dfcaa0cd9b262300243cf8ce91db26cbbc77e452c5f matching CI; signer 0ea8ca60075a1d1580ab3c9680c5d692938691a99492ce95165c1cfcbaf47e3a. Instrumentation ZIP 15045616804 SHA-256 ab1afefa91b1e0a2977059d3ee4dfb9d49dcabda241cfbbf14fb85b14e2a2d33 CRC verified: 11 reported tests, one initialization failure (remaining DB tests did not execute). Signing 45661523653 succeeded. Do not mark batch 7 verified.
- Fix explicit Unit/void return, including the new concurrent restore test, and extend label temperature test. Combine the repair with prepared batch 8 instead of triggering redundant intermediate CI. All cumulative instrumentation and advisory checks must pass/triage before release. Batch 8 scanner and test files independently matched exact Git blob IDs 04ff591570701c1dee2bcdfd9efed73aa16752d0 and c66ff8811d279423a64c2af567d753db37da672a and all five local offline unit tests passed. Network scan not yet executed. No user data, permission or signing changes.
- Next verify cumulative branch CI and exact artifacts, resolve actual failures and OSV findings. Known Kotlin build-cache CVE-2026-53914 requires a verified compatible toolchain upgrade; disabled cache exposure is not a patch. Physical device and trusted installed signer remain external gates. No merge/release/uninstall.

## 2026-11-25 — Audit remediation batch 8 (repeatable advisory evidence and restore contention)

- Batch 6 source 391a7219 passed Android 45656738734 and signing 45656738656. Downloaded build 15043596503 SHA-256 e51fd6dfbfc48f71a655597abe7e11f4baaa4373b2679f6de525726421ca28bd and emulator 15043127048 SHA-256 deea9236216b6e282a7e81ed1f553c7980b19f60d4028c7be3cb85e7bf901530 independently verified CRC and XML: 103 JVM, 34 emulator, zero failures/errors/skips, 11 signing policy tests. APK 1,057,175 bytes SHA-256 89cfefa45db6927bdbb11cea6f57b95ad48da092439f053d01f81d99f974b099 matches CI; exact five permissions, 65 lint warnings/0 errors. CI v2 debug signer 245d18f92e64d3e9b21633eb597e8819cd10cd991041a89dbbbc9b0ee7f1ba58; trusted installed signer unresolved. Batch 7 39aa8340 published and confirmed; Android 45661523315/signing 45661523653. Verify before advancing this batch.
- New dependency advisory collector sends only public Maven coordinates selected from runtime/test/buildscript dependency inventory to the documented OSV querybatch endpoint, validates scopes, excludes local project IDs, follows pagination and fails on incomplete results. Findings are reported for triage, not equated with exploitable app code. Five offline Python tests. Network response/CI still to verify. Coverage excludes SDK/native libraries, unmapped advisories and cryptographic artifact verification.
- Public advisory CVE-2026-53914 (https://nvd.nist.gov/vuln/detail/CVE-2026-53914, https://github.com/advisories/GHSA-r937-wjx7-w2jp) affects Kotlin build cache metadata before 2.4.20; current KGP 2.0.21 requires remediation. Disable restored Gradle cache, build/configuration cache and Kotlin incremental compilation as conservative exposure reduction, not a certified fix. Upgrading AGP/Gradle/Kotlin as a compatible set and revalidating remains required; do not assert a clean security result. CVE-2023-32697 SQLite JDBC fixed in 3.41.2.2; pinned 3.46.1.3 test-only dependency outside that specific affected range (https://github.com/advisories/GHSA-6phf-6h5g-97j2). No version change in this batch.
- Disposable DB concurrent reader/restore/recorder append instrumentation regression; restore retains sequence and WAL reader sees committed rows. Add charger-label interval-end temperature evidence, compile label validation patterns once, and record rejected recorder-event queue posts. No changes to hardware, existing sample values, schema, history formats, package, permissions, cadence or signing.
- Deliberate scope: no new encrypted format or device-bound identity; portable backups retain original compatibility, private storage and explicit unencrypted/unverified-provenance warning. Encryption or cryptographic provenance would require separately designed key recovery and migration, not a claim of protection. Actual device and trusted signer remain external acceptance gates. Next verify batch 7; if passed promote this batch and verify advisory report, contention tests and final artifact; if failed fix exact cause. No merge/release/uninstall or user-history deletion.

## 2026-11-25 — Audit remediation batch 7 (build hardening, privacy and scaling regression)

- Verified batch 5 source c1836075 with Android 45649625377 and signing 45649625450. Exact archives 15038328896 SHA-256 bebaf7579014e9089bdaf3e3c251dab76841e0fd52bfa65348f7820399a11ed4 and 15039980832 SHA-256 9b41d3d6ca3852318c28163950af6395382741c5dde31f82b91baf69791dd861 independently CRC/hash checked; 100 JVM, 33 Android, zero failures/errors/skips. Actual Activity recreation test passed. APK 1,049,319 bytes SHA-256 73eb5bcfb4af1fbd84ea5b036c61ca7077f3513b2ee7fd94744a4dd4bac1e535, CI v2 debug signer cd046a5a29b7a9006422238f4f4b4a7984cb329d0489d035f84a1a977eea68c0, lint 64 warnings/0 errors. Installed signer still unresolved. Batch 6 391a7219 promoted, Android 45656738734 and signing 45656738656; verify before advancing.
- Pin remaining top-level CI actions to exact upstream tag-resolved commits; disable checkout credential persistence. Verify the exact five packaged permissions rather than only INTERNET. Emit selected resolved runtime/test dependency versions; this is not locking, cryptographic dependency verification or a complete advisory scan. Public advisory API access through the connector was rejected; no clean security-scan claim.
- Explicit cloud and device-transfer backup exclusions for app-controlled storage domains, alongside existing allowBackup=false (Android developer docs: https://developer.android.com/identity/data/autobackup). User-selected exports remain possible, unchanged formats and unencrypted. Explicit pre-save privacy confirmation, CSV text-import caution, restore provenance warning in full report. No device ID or new personal data collected. Legacy backups cannot authenticate their source; OEM enforcement requires validation.
- New Android packaged-permission/backup-flag test and synthetic 50,300-row recent-overview equivalence regression with diagnostic timing, not a physical power/performance guarantee. No history deletion, schema, package, permissions or signing changes. Next verify batch 6 then batch 7 cumulative CI; inspect dependency inventory and actual advisory results where accessible, benchmark logs, documentation current status, final artifact. Device accuracy, installed signer and real power/soak remain external acceptance gates. No merge, release, hardware control, forced discharge, secrets or uninstall.

## 2026-11-25 — Audit remediation batch 6 (manual charger comparison labels)

- Parent batch 5 c1836075 is prepared and not yet accepted; batch 4 86efbca9 remains active branch pending instrumentation. Keep the sequence gated by CI. Batch 4 independently verified build archive 15037434717 SHA-256 8517c04d84489a828c4df2a1089132306d9fe4e81f2500c193a8f36633ab2797; CRC OK, JVM 100 passed, lint 65 warnings/0 errors, APK 1,047,771 bytes SHA-256 553e7289b92df08ccbbadb7d4daa3454d9522835b4ec9d6dcc1a54729cb1cdaa matches CI; single v2 debug cert 2b679830bd781b2a65a09917059a45599ab060e72f448186f1d1c5ccb20ac398. Not an installed signer match.
- Add manual non-personal charger nickname, scoped to active recorder process and observed connection. Clear at observed power-connected/disconnected or changed/unknown source, stop/restart; require fresh assignment and future sample acquisition. Persist optional metadata in existing power_supply_json; no schema/CSV/backup changes. Assignment nonce is local random grouping token, not device identifier. No retrospective assignment or automatic identity/protocol inference. Missed broadcasts can make scope incomplete.
- Detailed report compares up to 16 exact nickname/source groups, per SOC stage, requiring same valid assignment at both endpoints and existing timing/boot/source/level checks. Same nickname across assignments aggregates eligible intervals without joining the sessions; labels are unverified manual/imported assertions, not controlled comparisons. Passive overview does not query or parse label JSON. Two JVM tracker tests, one profile regression and one DB/backup regression.
- CI pending for batch 5 then batch 6. Remaining real lifecycle/device validation, recorder overhead and long-history benchmarks, privacy/provenance and dependency/reproducibility/docs. Preserve all data, package, permissions and signing; no charging control, hardware modification, speculative electrical conversion, force discharge, uninstall, secrets, merge/release.

## 2026-11-25 — Audit remediation batch 5 (activity recreation and recorded connection evidence)

- Batch 3 aa2c6fbc passed Android 45648355483 and signing 45648355664. Downloaded exact artifacts 15039241918 SHA-256 4be157d8b352e9b87d73e9d606ffec10ea1a71e1b38c23e25234396eaa7b80cc and 15039855047 SHA-256 9a4b933dac291081b25b41f357b0896dfda0ebdcd16c131f4f2175ccd811ba4a; independently checked SHA/CRC and XML (100 JVM, 28 emulator, zero failures/errors/skips). APK 1,045,383 bytes SHA-256 2d6d22267089e3e27281e2faa2cd076f9941e33a037eedac29a651cad4529fe2 matches CI; CI apksigner single RSA v2 debug cert 5b108f42a9b271db6f2c5cb9d2e9cd292147999253691d8144e7954b3fb48d54, not trusted installed identity. Lint 66 warnings/0 errors. Batch 4 source 86efbca9 promoted and confirmed; Android 45649019072, signing 45649019131. Verify before publishing this batch.
- Preserve tools expansion and scroll offset in Activity state across recreation; wait for overview layout before restoring, clamp to actual extent, cancel delayed restore on explicit touch. Actual Activity instrumentation test opens real dashboard on disposable CI emulator, verifies pause remains respected across refresh and recreation, plus widget restoration regression. Paused service startup exceptions no longer escape the stop path.
- Add separate bounded recorded connection event timeline to detailed charging report, using the same captured sample/event ID bounds. Retain up to 24 actual retained power broadcasts/state-source events, excluding future/old/unrelated events; distinguish sampled evidence, imported data and wall-time uncertainty from protocol messages. No extra passive scans. Database regression checks scope/order and unchanged backup bytes.
- No schema, history retention, CSV/backup format, permission, app identity or signing changes. New CI required; actual Activity test and user handset remain unverified. Remaining explicit charger labels, privacy/provenance, scaling/overhead benchmarks, dependency/reproducibility and documentation consolidation. No hardware control, forced discharge, speculative calibration, uninstall, secrets, merge/release.

## 2026-11-25 — Audit remediation batch 4 (bounded passive history work)

- Batch 2 source 46fd7819 verified Android CI 45645894442 and signing 45645894431. Exact downloaded archives 15036129959 and 15038940047 matched GitHub SHA-256 and CRC; 100 JVM, 27 Android, 11 signing tests passed; lint 66 warnings, 0 errors. APK 1,043,715 bytes SHA-256 ade06d3ef4fd809b83ebf541dd61f5c6d2e07880deadec5b9fc472e335d8ae06; CI verified v2 debug signer 5dac011d87ef550e0ed6114ee055bc2906b097b8e1dbb16f3c1f77bda3d6c8ee. Not a trusted installed signer. Batch 3 source aa2c6fbc published, Android 45648355483 and signing 45648355664; verify before advancing this batch.
- Passive dashboard overview now reads only last 30 days of sample rows using immutable ID bounds without all-history count, event scan, all-history aggregation or separate capacity report. Reuse the same charging profile algorithm and typed stages/curves; detailed all-history and 90-day capacity reports computed asynchronously only when requested, with one on-demand report in flight. Results are stable snapshots, not live rewritten dialogs. Manual Refresh also explicitly refreshes overview; failures show unavailable rather than stale graphs. Two database instrumentation regressions verify overview equivalence, exclusion of future/old samples, empty data and unchanged backup bytes.
- No schema, retention, CSV/backup, permissions, recorder cadence, app identity or signing changes. This reduces query scope by construction; physical CPU, power, memory and long-history timing are not benchmarked. Future work: actual Activity integration/lifecycle tests, incremental 30-day scaling, charger-label provenance, import privacy/contention, CI dependency/reproducibility and docs. Device validation and trusted signer are external gates. No merge/release/uninstall or user-history mutation.

## 2026-11-25 — Audit remediation batch 3 (persistent user-controlled recording pause)

- Batch 1 source 5683ee86 passed Android 45645106338 and signing 45645107272. Independent archive digest/CRC and test XML checks: 99 JVM, 26 emulator tests, no failures/errors/skips; lint 63 warnings, 0 errors. APK SHA-256 25bfaeef1f90454e460b0b4a2763ad40b27573c750fff8c1e551ba962d9ae739; CI v2 debug cert 4b6f6070bddbb1d5861ea9a43681b449d06e4ce8ee001d62ab5fcd30a3538d0d, not trusted installed identity. Batch 2 source 46fd7819 advanced after checks; Android CI 45645894442 must finish before publishing this batch.
- Add explicit pause/resume behind history/settings tools with confirmation and independent persisted preference. Existing automatic start behavior remains default unless user pauses. Boot/package-update receiver, Activity startup/reconfigure and sticky/delayed service start respect pause. Live readings remain available; existing history not deleted. A currently executing sample may finish; the service stop is asynchronous. Added isolated preference regression; real lifecycle/reboot/handset and overhead tests still required. No new permissions or data formats.
- Next verify batch 2 then this batch, prioritize remaining history-performance and restore/recorder contention checks, actual Activity integration, charger evidence/source labeling, dependency and docs checks. Device electrical validation and trusted installed signer remain external. No forced discharge, privileged access, charging control, uninstall, merge/release or data deletion.

## 2026-11-25 — Audit remediation batch 2 (structured summary and validation)

- Build on batch 1, source 5683ee86. Pass typed charging-stage snapshot from same database scan as detailed report and curves; eliminate English report parsing in dashboard. Add concise visible recorder health, skip legacy application-attribution work in charging-focused reports without changing historical raw data.
- Check available storage before each staged import chunk, retain final transactional restore preflight and rollback; conservative estimate is not a storage guarantee. Share optional broadcast value validation between recorder and dashboard (wrong types and advertised/cycle sentinels rejected from normalized fields, raw metadata preserved). Add staging and optional-broadcast regression tests; update structured-summary test.
- Scope unchanged: no hardware control, schema, permissions, app identity, signing or backup/CSV format changes. New cumulative CI required. Remaining audit work: real activity and device tests, recorder opt-in/pause and overhead, incremental history scaling, provenance and backup protection, CI dependency/reproducibility checks, documentation cleanup, trusted signing and electrical validation. Do not merge/release/uninstall or modify user history.

## 2026-11-25 — Audit remediation batch 1 (charging completion, evidence, icon)

- Previous source 0146c945 passed Android CI 45597784906 (97 JVM, 25 emulator) and signing CI 45597784793 (11 policy tests). Audit checkpoint PR comment 7161471272.
- Corrected final 100% FULL endpoint handling for stage spans without counting transition as confirmed active rate; exclude post-100% plateau; add bounded durations for skipped stage boundaries, retain exact observed spans and never interpolate. Same-source, same-known-boot, eligible intervals only. Historical report source continuity corrected; contradictory CHARGING/NONE kept in decline diagnostics rather than positive charge rate. Added bounded 24-change sampled source/state/advertised-limit timeline and Android-source-category comparison, explicitly not physical adapter identity or proprietary packets. Existing live report remains read-only.
- Replaced launcher battery/lightning icon (normal and round adaptive resources share foreground; added Android 13 monochrome variants). Min SDK 26; no older legacy icon needed. Added focused JVM and Android regressions, dashboard bounded-span summary. New CI pending. No schema, history, export, permissions, app identity or signing changes.
- Next: verify this exact cumulative CI/artifacts; address failures before further scoped changes. Remaining audit work: typed dashboard data and integration tests, recorder pause/overhead and long-history scaling, import preflight/privacy, sentinel/cache consistency, build reproducibility/dependency checks, docs consolidation. Trusted installed signer and actual electrical/device validation remain external gates. No merge/release or forced discharge, hardware changes or uninstall.

## 2026-11-25 — Compact charging dashboard and passive-refresh scroll fix

- User reported excessive text and automatic scrolling on refresh. Previous activity rewrote multiple large selectable TextViews every two seconds. Main screen now shows concise battery/charger summary, three charging stages and native graphs. Full readings, raw charger/phone communication evidence, quality, history, capacity, recorder status and measurement rules remain accessible through explicit snapshot dialogs. Open dialogs do not receive live text updates; reopen to refresh. Export/backup/import/settings controls and operation messages remain behind a user-controlled tools section. Chart checkpoints collapse by default.
- Passive refresh avoids identical text writes, uses non-selectable main-screen text, and preserves current scroll offset across refresh-triggered layouts (clamped if content shrinks below it). Capture at layout time respects intervening user scrolling. Actual handset acceptance remains required. Added Android scroll and summary regressions and extended chart test for collapsed/expanded checkpoints. No collection, database, CSV/backup, permissions, identity, signing or control changes.
- Prior stage source 01199413 passed Android 45552713865 and signing 45552713872. New cumulative CI required. Next inspect exact branch CI and repair actual failures; verify debug artifact and device acceptance before update. Trusted installed signer remains unresolved. No merge/release/uninstall or history mutation.

## 2026-11-25 — User-prioritized charger/phone/battery evidence and charging stages

- User clarified that the primary objective is charger/phone/battery communication evidence and 0–20%, 20–80%, 80–100% charging performance. Existing ordinary-app telemetry already separates Android battery state/current/temperature, advertised maxima, readable raw external/BMS/USB-C/PD nodes and unavailable charger input. No direct proprietary handshake capture, privileged access, hardware modification or charging control. Phone is the intermediary; raw flags are not protocol packets.
- Added independent coarse SOC-stage interval accumulators, sampled temperature ranges and completed exact-boundary span duration min/max/count, using the same existing bounded history scan. Exclude cross-stage jumps, invalid intervals, reversals and gaps; no interpolation or extrapolation of missing boundary observations. Retain finer 10% detail. Two focused JVM regressions. New cumulative CI required; no schema, export, identity, signing, permissions, recording cadence or history changes.
- Previous accepted software source 3cf01307 passed Android 45538645487 and signing 45538645360, 95 JVM + 23 emulator tests; documentation checkpoint 05f1e57 records artifact verification. New stage scope supersedes software stop condition only for this incremental change. Next: verify new cumulative Android/signing CI and artifacts; address actual failures. Physical handset acceptance, trusted installed signer, OEM electrical units and actual charger input remain external blockers. Do not force discharge, uninstall, alter history, solicit keys or infer protocol messages.

## 2026-11-25 — Charging-first software acceptance checkpoint (supersedes older status entries)

- **Verified source:** `3cf01307a827cbaa1c0826e6e5daf5517af831e3`, draft PR #3, branch `fix/apk-update-preflight`. Android CI [45538645487](https://github.com/astramark06-sudo/Battery-only/actions/runs/45538645487) and signing-policy CI [45538645360](https://github.com/astramark06-sudo/Battery-only/actions/runs/45538645360) completed successfully. This checkpoint changes documentation only; source, build configuration and test files are identical to the verified source. No additional build is necessary for this documentation change.
- **Completed scope:** charging-only reporting, observed SOC-band speeds, connected-state duration partition, measurement rejection reasons, advertised-limit history separate from electrical measurements, native SOC/temperature plots with bounded segment selection, full observed temperature extrema before point thinning. No fabricated values or new unrelated telemetry. The app preserves existing database/CSV/portable backup formats and application identity.
- **Exact artifact evidence:** downloaded debug artifact 14987608371 (ZIP 1,105,323 bytes; SHA-256 `2b94a6378ac51e9425e6438dbe29fd2bd7fa64be21f3ee6e91c3004b9bfe858f`) and instrumentation artifact 14988302542 (ZIP 71,130 bytes; SHA-256 `7c3141a2be67330bb73049a97a26cfd4ccfa760099df3cc71c78c94b4437d74e`). Both hashes independently matched published metadata and ZIP CRC checks passed. Downloaded XML independently confirms **95 JVM tests in 22 suites and 23 Android instrumentation tests, all passed, no failures/errors/skips**. Eleven charging-profile JVM tests include the full-temperature-range regression; instrumentation includes Canvas drawing, selection, missing/omitted temperatures, history migration, rollback, read-only snapshot and CSV/backup regressions. Lint XML: 80 warnings, zero errors; localization warnings remain non-blocking debt.
- **APK:** 1,028,338 bytes, SHA-256 `8aeffc9a82f32f19f754ea9754e5a86e61e8c23d69aac22e9a7b67263c37fb60` independently matched included checksum report; CI badging confirms com.batteryscope v1.15/16 debug, minimum SDK 26, target SDK 35, no INTERNET permission. CI apksigner report verifies v2, single RSA debug certificate SHA-256 `8a16007fab9531c71f30139e3b0b591ce9f3b5348719581926b88f391cdc199c`. Prior checkpoint recorded supplemental RSA-v2 signature/digest and tamper checks on this same APK hash. Not a trusted installed-app update.
- **Software stop condition:** agreed implementation and cumulative automated tests complete; no pending source candidate or evidenced software defect requiring another change. Do not manufacture new features or rerun unchanged tests. No merge/release or device changes authorized. Automation stays paused.
- **Outstanding external acceptance:** independently establish installed package signing certificate and version, preserve existing installation/history, and use the trusted-owner signing/preflight process in SIGNING_AND_UPDATE.md before any update. Then verify chart rendering, recording continuity, CSV/backup history preservation, and measurement-source behavior on the actual RMX2161. Actual charger input and OEM voltage/current units remain unverified; unavailable data must remain unavailable. Do not request keys, uninstall, delete data, modify hardware or guess calibration. Exact next action: obtain independently trusted installed certificate/version evidence (not whole-file hash), or acknowledge signing is blocked; no safe independent source work remains unless new evidence identifies a defect.

## Historical pre-acceptance status (superseded by checkpoint above)

Current source layout: RecorderDatabase now resides in `app/src/main/java/com/batteryscope/RecorderDatabase.kt`, separate from the foreground service. Optional Usage Access supports bounded package-transition import with transactional checkpoints and deduplication. These new source changes require cumulative CI; see DEVELOPMENT_HANDOFF.md.

## Current development status — 2026-11-25

Active work is in [draft PR #3](https://github.com/astramark06-sudo/Battery-only/pull/3), branch `fix/apk-update-preflight`; main is the previous baseline, not the current remediation source. See [DEVELOPMENT_HANDOFF.md](DEVELOPMENT_HANDOFF.md) for the latest checkpoint and [PR CI](https://github.com/astramark06-sudo/Battery-only/pull/3/checks) for exact verification outcomes. Package com.batteryscope, version 1.15 (16), database v5. No release or data migration on the physical RMX2161 has been authorized as verified.

Recent source changes include WAL and one-pass rolling history, bounded recent-session processing, verified export staging and recovery, strict boot/permission-aware rates, raw broadcast metadata, six resource-history windows, expanded public API inventory (normal ACCESS_NETWORK_STATE; no INTERNET), release metadata checks and lint, parsed JSON resource selection, cooperative kernel inventory limits, and sequence-preserving CSV. These are source changes, not a claim that every latest test has passed.

The earlier cumulative run 45481677171 failed lint and two emulator assertions; corrections were committed in 11a7ebe and require fresh cumulative CI. Remaining gaps: durable Android system-history imports, backup provenance/overlap policy, scalable persistent aggregation, lifecycle/provider tests, device benchmarks, signing continuity, and real handset validation. Do not install an ephemeral debug APK over an existing installation or delete history.

## Historical status and evidence

The sections below describe earlier versions and branch checkpoints; dates, schema descriptions, CI counts, permissions and feature descriptions are historical, not current release claims.

> **Repository migration — 2026-11-24:** Current development is in [Battery-only/main](https://github.com/astramark06-sudo/Battery-only/tree/main). The former `batteryscope` branch in `ocr` is historical, not the active development branch. The [standalone Android build](https://github.com/astramark06-sudo/Battery-only/actions/runs/45348612049) passed for commit `45d65c6b624515bfd269e1eaa6380cb4611e050a` (artifact ID `14899402289`). Earlier source commits, checksums and signer records below remain historical evidence; no new device or signing-compatibility verification is claimed.

Last source update: 2026-11-23  
Active branch: main (Battery-only)  
Current source version: 1.15  
Current version code: 16  
Application ID / namespace: com.batteryscope  
Target handset: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Architecture

BatteryScope is a standalone Android application. It uses standard Android APIs and ordinary-app process access only. It has no Shizuku dependency, provider, AIDL service, ADB setup, root requirement or companion-app requirement. The privileged charging-control path has been removed; BatteryScope does not write sysfs.

## Source layout

- `app/src/main/java/com/batteryscope/MainActivity.kt` — UI, Android telemetry, ordinary-app read-only diagnosis, automatic recorder recovery, capacity panel and CSV picker.
- `app/src/main/java/com/batteryscope/BatteryRecorderService.kt` — foreground recorder, worker thread, event capture, ordinary-app sysfs inventory, SQLite schema/history analytics and CSV writer.
- `app/src/main/java/com/batteryscope/BatteryCapacityEstimator.kt` — pure estimator using charge-counter/SOC session deltas.
- `app/src/main/java/com/batteryscope/BatteryRateMath.kt` — tested mA rate and SOC forecast calculations.
- `app/src/main/java/com/batteryscope/BatteryPercentMath.kt` — validated battery level/scale conversion.
- `app/src/main/java/com/batteryscope/BatteryMeasurementQuality.kt` — current-polarity and voltage-range validation before watt calculation.
- `app/src/test/java/com/batteryscope/BatteryRateMathTest.kt` — pure calculation tests.
- `app/src/test/java/com/batteryscope/BatteryPercentMathTest.kt` — missing/invalid percentage metadata tests.
- `app/src/main/java/com/batteryscope/BatteryRecorderBootReceiver.kt` — automatic BOOT_COMPLETED and MY_PACKAGE_REPLACED resume attempt.
- `app/src/main/AndroidManifest.xml` — Android permissions, launcher, boot receiver and foreground-service declaration.
- `app/src/test/java/com/batteryscope/BatteryCapacityEstimatorTest.kt` — capacity-estimator unit tests.
- `app/build.gradle.kts` — application identity, version and Android dependencies.
- `README.md`, `AUDIT.md`, `CHANGELOG.md`, `RELEASE_CHECKLIST.md`, `AGENTS.md` — product and engineering documentation.

## Current capabilities

The app collects Android BatteryManager battery-side measurements and best-effort read-only class-tree attributes visible to the ordinary app process. The recorder stores 30-second framework samples and 120-second class-tree samples in a local SQLite database. SQLite schema v4 adds optional charger-advertised maximum current/voltage, battery presence, Android invalid-charger flag and cycle-count metadata with non-destructive sequential migrations from v1/v2/v3. Local SQLite migration simulations from all three prior schema versions preserved existing rows. Live dashboard reads now run on a dedicated HandlerThread rather than the UI thread. Diagnostics report per-root visibility, read/list errors, Android property unavailability and Usage Access state. Existing history summaries and CSV export remain local. History reports percentage/hour and charge-counter-derived mA rates, screen-on/off comparisons, plugged-in battery decline, app foreground-time and charge-counter correlation, recent sessions, SOC threshold times and historical time-to-full/time-to-empty projections. Recent charging/discharging session reports also calculate display-on/display-off rates and separate time-to-threshold forecasts; display state is a proxy, not exact touch activity.

The new Battery Health panel estimates full-charge capacity from charge-counter movement relative to percentage movement across continuous charge/discharge sessions. It separately reports readable kernel fuel-gauge charge_full/design-capacity ratio and cycle count, with explicit missing/denied/hidden diagnostics. It examines up to 90 days, requires at least 12 percentage points of movement and consistent counter direction, uses the median of valid session estimates, and reports session count, spread and confidence. Design capacity is set to 4,500 mAh for the RMX2161. If Android does not expose a usable charge counter, no capacity value is fabricated.

## Measurement boundaries

- BatteryManager current/voltage and derived watts are battery-side, not USB input power.
- The charge counter is remaining-charge telemetry, not FCC by itself.
- Capacity health is an estimate from recorded charge-counter/SOC changes, not a laboratory test.
- Android health GOOD is a framework status, not capacity health.
- USB V/I candidates require an external supply identity and still need on-device driver-semantic validation.
- PDO records are advertised capabilities, not proof of the selected active contract.
- VOOC/SuperDart flags are driver state, not protocol packet capture.
- Foreground-app battery decline is correlation, not direct per-app electrical measurement.
- Missing or denied values remain unavailable, not zero.

App-source verification checkpoint branch head: `f78c9e63c63652b6dc8d600623482d9a18dee043`. The verified APK was built from app-source commit `480a567cd7c90f552976dc60472fa6ef11a5e126`; comparison to the final branch head shows only docs/workflow changes after that commit, with no Android app source/build changes.

## Verification state

- Source version 1.15 / versionCode 16 was originally committed to branch `batteryscope`; app source commit used for the verified build is `480a567cd7c90f552976dc60472fa6ef11a5e126`.
- Capacity estimator unit tests cover nominal capacity, small-SOC-change rejection, counter-direction mismatch and missing boot-count rejection.
- Battery rate math unit tests cover battery-side mA calculations and SOC time forecasts; battery percentage math tests reject missing or invalid Android level/scale metadata.
- Source-level checks completed: no Shizuku/privileged service references in MainActivity, recorder service, manifest or Gradle dependencies; CSV header/sample/event row alignment verified at 28 fields; isolated Kotlin arithmetic checks passed for battery-side mA and SOC forecasts.
- Fixed a duplicate MainActivity lifecycle override that would have blocked Kotlin compilation. Recorder event writes and orderly database shutdown were moved off the Android main thread; the boot receiver no longer opens SQLite during BOOT_COMPLETED.
- The authoritative GitHub Actions workflow `batteryscope-android.yml` builds/tests the app, verifies package/version metadata and APK signature, and uploads artifacts. The duplicate legacy `batteryscope-build.yml` workflow was removed because it produced a second debug APK signed by a different ephemeral key.
- BatteryManager property diagnostics distinguish unavailable sentinels, SecurityException and other exception classes. Version 1.15 additionally validates current polarity and voltage range, records quality transitions and excludes invalid values from battery-side wattage.
- v1.15 Android Gradle build and all 22 JVM tests passed in workflow run 45223861385. No failures, errors or skipped tests.
- v1.15 APK verified: 914,326 bytes; SHA-256 `0c8f37eb4777b9ece9a1241a85e4805bab06dd5fd4b0bdde339f748c384b32ac`; CI artifact ID `14840939491`, ZIP SHA-256 `2c0b385d7416471e7c40817a5d2fe34aa05a2eaaf624307bfeebf001bb31195c`; run `45223861385`. Debug signer certificate SHA-256 `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`.
- No install or runtime test has been performed on the physical RMX2161. The v1.15 debug certificate (`fe463192…5094c2`) differs from the v1.14 debug certificate (`3c75edb5…194b5`); this APK cannot update that installation in place. Do not uninstall or overwrite the existing app/data.
- Actual charge-counter availability and capacity-estimate accuracy on this handset remain unverified.

## Physical RMX2161 validation

1. Install the exact final APK and verify update compatibility and preserved local history.
2. Confirm BatteryManager charge-counter readings are present and change plausibly while charging and discharging.
3. Record multiple sessions with at least 12 percentage points of change; compare the resulting estimate across sessions.
4. Check confidence/spread behavior and ensure missing charge-counter data produces an unavailable result.
5. Verify recorder notification, screen-off sampling, wake-lock power impact, CSV export and app-private history.
6. Test boot-resume behavior and Realme background-management restrictions.
7. Confirm no Shizuku, root, ADB or companion-app setup is requested or required.


## Device-wide monitoring expansion — in progress (2026-11-23)

The product objective has expanded from battery-only monitoring to a permanent, local-first device telemetry platform. The full capability matrix, Android API/permission classification, history-import plan, RMX2161 device census, lifecycle design and phased acceptance gates are in [DEVICE_TELEMETRY_ARCHITECTURE.md](DEVICE_TELEMETRY_ARCHITECTURE.md).

Source changes now request recorder startup automatically when the app resumes, resume on BOOT_COMPLETED and MY_PACKAGE_REPLACED, and remove the in-app Start/Stop controls and service stop action. The boot receiver no longer gates restart on the former user-enabled preference. The ongoing foreground notification remains. This lifecycle source is CI build-verified in v1.15 but has not been tested on the physical RMX2161.

Newly identified Android data sources for later implementation:
- UsageStatsManager events and daily/weekly/monthly/yearly aggregates (requires user-granted Usage Access; detailed events expire after a few days).
- NetworkStatsManager mobile/Wi-Fi and per-UID byte buckets (requires access and device verification).
- StorageStatsManager per-package storage usage (queries can take seconds and must run off-main-thread).
- PowerManager thermal status/headroom, ActivityManager memory information, SensorManager sensor inventory, and best-effort CPU/GPU/thermal sysfs sources.
- BatteryStats per-UID energy and full system battery history remain privileged; no ordinary-app workaround is assumed.

The RMX2161's exact kernel exports, UsageStats/NetworkStats query behavior, SuperDart input telemetry, sensor inventory and Realme background policy remain unverified until an on-device capability report is collected. No new device-wide collector is marked implemented merely because its API has been identified.


## Historical Android CI checkpoint before v1.14 verification — 2026-11-23

The dedicated Android build on commit `81286c706390d6c60d94a60428c8b298fa0651d2` failed during Kotlin compilation, not SDK setup. The exact compiler diagnostic was an unterminated string / incorrect template entry at `MainActivity.kt:407:204`. The offending bounded-kernel-scan warning string is fixed in commit `8463bb34afa81b2ba26e5598342ad8c5f798ce78`. A new APK workflow run (`45211353283`) was started for that commit and must be inspected before claiming build success. The failed run created no APK and uploaded no build artifact. General repository CI passed on the previous checkpoint; that does not validate the Android app.


## Latest Android CI checkpoint — v1.15 — 2026-11-23

The authoritative workflow [45223861385](https://github.com/astramark06-sudo/test/actions/runs/45223861385) succeeded for app source commit `480a567cd7c90f552976dc60472fa6ef11a5e126`. All 22 JVM tests passed; APK assembly, package/version verification, SHA-256 and v2 signature verification succeeded. Artifact ID `14840939491`; APK size 914,326 bytes; APK SHA-256 `0c8f37eb4777b9ece9a1241a85e4805bab06dd5fd4b0bdde339f748c384b32ac`; signer certificate SHA-256 `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`.

The v1.15 signer differs from the v1.14 debug signer. It is not update-compatible with the existing v1.14 installation. No install/overwrite was attempted. The duplicate legacy build workflow was removed after it produced a second APK with identical uncompressed contents but a different debug signature; `batteryscope-android.yml` is the authoritative build/verification workflow.

## Latest handset-export validation — 2026-11-23

Reviewed user-provided `BatteryScope_History_20261123_214733.csv` from the RMX2161. The export contains 22 SAMPLE rows and 51 EVENT rows over 13 min 35.654 s (UTC 16:03:51.492–16:17:27.146; 21:33:51–21:47:27 India time). Battery SOC fell from 83% to 80%; Android charge counter fell from 3,735,000 to 3,600,000 µAh; temperature ranged 33.8–35.4 °C. All samples report NOT_CHARGING / plug NONE, so this export contains no charging session and cannot validate charging watts.

Critical measurement finding: `battery_voltage_mv` contains only raw values 3 or 4, although the source labels the field mV and the live dashboard accepts only 2,500–5,000 mV. Consequently, the current dashboard will reject these voltage values and cannot calculate battery-side watts. `battery_current_ua` contains 14–674 (mean 201.9; median 132) and is treated as µA by the app, yielding only 0.014–0.674 mA. These magnitudes do not reconcile cleanly with the 135 mAh charge-counter decline over the capture interval (coarse endpoint-derived average about 595.8 mA). This is evidence of a device/API scaling or counter-quantization issue, not proof of a particular conversion factor. Do not multiply or relabel these values automatically until verified against a known-good RMX2161 reference or independent measurement.

Average-current property, energy counter, cycle count, charger-advertised maxima and USB voltage/current candidates were absent in all samples. Usage Access was off in 20/22 samples and on only in the final two. Twenty-one sample intervals averaged 38.84 s; one interval was 154.741 s (2 min 34.7 s), exceeding the 90 s analytics continuity threshold. Screen state changed during the capture. Kernel root diagnostics in the export report READ_DENIED or ABSENT_OR_HIDDEN_BY_SELINUX, with zero captured fields.

Disposition: recorder and CSV persistence are demonstrated for this short session; measurement-unit correctness, battery-side watt calculation, USB-input watts, long-duration continuity and device-wide collectors are not validated by this file. Preserve raw values. Next engineering action is to add explicit raw-value/unit diagnostics and handset-specific validation, then test charging and discharging captures separately. Do not claim 24/7 continuity or charger-input wattage from this export.


## Forensic audit and v1.15 correction — 2026-11-23

See [FORENSIC_AUDIT_2026-11-23.md](FORENSIC_AUDIT_2026-11-23.md) for the source-tree/APK integrity review and extended CSV findings. Version 1.15 now validates BatteryManager current direction against Android's documented convention, validates battery voltage in mV, prevents wattage calculation from invalid inputs, logs quality transitions, simplifies the main telemetry panel and retains UsageStats source event timestamps. Eight tests were added. No database schema migration or package identity change was made.

**Still incomplete:** NetworkStatsManager, StorageStatsManager, SensorManager, ActivityManager memory, installed-app inventory, CPU/GPU collectors and other device-wide telemetry remain unimplemented. No physical-device or 24-hour continuity validation has been completed.


## Extended handset-export validation — 2026-11-23

The second user export contains 55 SAMPLE rows and 122 EVENT rows over about 46m23s. Battery level fell 83% to 74%; charge counter fell 3,735,000 to 3,330,000 µAh (405 mAh); temperature ranged 31.8–35.4 °C. Android recorded 52 NOT_CHARGING/NONE samples and three CHARGING/AC samples. During unplugged samples, current values were positive (14–674); during the three charging samples they were negative (-551 to -560). This conflicts with Android's documented current polarity. Voltage remains 3–4 in the mV-labelled field. Version 1.15 now flags both issues and refuses to calculate battery-side watts from them. It does not infer actual USB-input power.

There were seven sample gaps longer than 90 seconds; the longest was 307.790 seconds. Four sampling-mode changes occurred. This remains insufficient to prove 24/7 continuity. The extended audit also confirms that device-wide NetworkStats, StorageStats, SensorManager, RAM, CPU/GPU and installed-app collectors remain unimplemented.
