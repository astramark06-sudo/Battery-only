# BatteryScope comprehensive engineering audit — 2026-11-25

## Verdict and scope

**NOT RELEASE READY.** The draft development APK compiles and passes its existing tests, but measurement validity, data durability/coverage, update signing and the device-wide product scope remain incomplete.

Audited repository: `astramark06-sudo/Battery-only`; draft PR #3 branch `fix/apk-update-preflight`; pinned source checkpoint `17c61bdfaa23b24860d32598f9ea1d245c975710` (documentation-only successor of tested head `54c9efa80a147c4093de5985cc8d24058373be05`). CI tested merge ref `cefed72488be9cd7aafcc013fa7fb6c26741e921`. This audit is a static source/requirements review plus independent inspection of the exact downloaded artifacts and existing test reports, not a fresh Android build, complete penetration test, or physical-device validation. No source edits or merge performed. File inventory: 64/64 tracked paths enumerated (19 production Kotlin, 15 JVM test Kotlin, one instrumentation Kotlin, five XML, two Python, two workflows, three Gradle KTS, one properties, one gitignore and 15 Markdown). Production Kotlin logic, manifest, configs, resources, signing helper, test inventory/assertions, prior engineering reports and roadmap were inspected; documentation review is targeted, not proof every historical assertion is correct. Direct Git clone unavailable in the local runtime; source was retrieved via GitHub connector. No measured coverage percentage is available. Do not claim 100% runtime verification.

## Exact build and test evidence

| Item | Verified result |
|---|---|
| Android build + JVM test | [Run 45468739015](https://github.com/astramark06-sudo/Battery-only/actions/runs/45468739015), passed |
| JVM reports | 15 suites, 52 tests, 0 failures, 0 errors, 0 skipped; independently parsed downloaded XML |
| Android instrumentation | 4 tests, 0 failures/errors/skips; independently parsed artifact XML, Android 12L emulator; migration, typed backup roundtrip, overlap/corruption rollback, CSV |
| Signing policy | [Run 45468739077](https://github.com/astramark06-sudo/Battery-only/actions/runs/45468739077), 8 tests passed per CI log |
| APK artifact | 14957875318; 989,156-byte ZIP, CRC OK; SHA-256 `e2cbbba0ecc5829f989afb390106c6d5baee5ebe009154adf4e3ce142e5878d1` |
| APK | 961,918 bytes; CRC OK; SHA-256 `a3e4980338ce0aff1ae97a5c77b4c71537d972d2d5bb0d54e658598d94172cd7` |
| Signature | Independently parsed v2 signing block, matched DER certificate and public key, verified RSA PKCS#1 v1.5 SHA-256 signature and recomputed signed content digest `94f9850f5ac11e38f899746a8991ff4d7f3a6469276028f994a1617f70335c91`; also matches CI apksigner report |
| Certificate | Android Debug RSA 2048; SHA-256 `c63b35c09c7ca7a8533a76b3d36cb19e1b09366d8dc90d05b9952031f6611df9`; differs from both documented historical certificates |
| Instrumentation ZIP | 14955683583; CRC OK; SHA-256 `dbfe028960b02a8f27979a92226d096f73b50524ff162da2744a882f8507850c` |
| Binary manifest | Independently decoded: com.batteryscope; 1.15/16; min SDK 26, target 35; debuggable=true, allowBackup=false; only exported component is launcher; recorder and boot receiver nonexported |
| APK inventory | 16 ZIP entries; 3 DEX files; no native libs/assets. No full DEX decompilation or dependency CVE scan performed. |

**64 existing tests passed across three suites; none proves device-wide completeness.** No independent Android emulator/Gradle execution in the local environment; prior CI execution and actual report contents verified. APK signature checks do not establish installation compatibility or safety of every behavior. No handset or trusted installed baseline/key available.

## Findings register

Severity: Critical = blocks release; High = significant correctness, integrity, privacy or core-scope issue; Medium = important functional, reliability or quality gap; Low = maintainability/documentation. These are audit items, not a count of reproduced crashes. Classification distinguishes directly observed source behavior, architectural limitations, risk and test gaps. Source links pin the audited commit.

Total 40: 2 critical, 18 high, 18 medium, 2 low.

### R01 — Critical: Signing identity mismatch and unchanged version

- Classification: Confirmed release blocker.
- Location: [Gradle](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/build.gradle.kts); APK certificate and version.
- Evidence/impact: The new signer c63b35c…11df9 differs from both documented historical signers. The installed certificate is not independently known. Version code remains 16. The build is debuggable.
- Proposed correction: Recover trusted installed certificate and original signing identity privately; build a non-debug release with increased versionCode; run real baseline preflight. Never uninstall to bypass this gate.
- Acceptance check: Exact same-signer update; retained history; version advance; non-debug manifest; independent APK verification.

### R02 — Critical: RMX2161 current and voltage are not validated

- Classification: Confirmed device evidence; unresolved.
- Location: [BatteryMeasurementQuality.kt:16](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryMeasurementQuality.kt#L16); historical device forensic report.
- Evidence/impact: Historical exports report raw voltage 3–4 in an mV field and opposite current polarity during charge/discharge; magnitude also disagrees with counter-derived rate. Current implementation correctly suppresses some invalid calculations but no calibrated replacement is established.
- Proposed correction: Preserve raw values, mark units/polarity unverified on this firmware, cross-check safe normal-use captures against trustworthy independent measurements; no guessed scaling or sign flip.
- Acceptance check: Charging and discharging references agree within a documented tolerance; otherwise explicitly unavailable.

### R03 — High: Current magnitude has no plausibility bound

- Classification: Confirmed logic defect.
- Location: [BatteryMeasurementQuality.kt:22](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryMeasurementQuality.kt#L22).
- Evidence/impact: Sign-only validation accepts +2,147,483,647 µA at 3,850 mV while CHARGING; formula yields about 8,267.8 W. Sign match does not establish correct units.
- Proposed correction: Use source-aware plausible bounds, sentinel handling and independent calibration; preserve rejected raw data.
- Acceptance check: Normal, zero, extreme, wrong-sign, ambiguous-state and unit-mismatch cases.

### R04 — High: Nominal-proportional gauge can receive misleading HIGH confidence

- Classification: Confirmed limitation.
- Location: [BatteryCapacityEstimator.kt:62](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryCapacityEstimator.kt#L62).
- Evidence/impact: Nominal detection is global allPairsNominal; a single off-nominal pair anywhere, even outside an accepted session, defeats the warning. Five tightly clustered nominal-derived sessions can then report HIGH confidence. Spread is repeatability, not independent accuracy.
- Proposed correction: Evaluate proportionality and quantization per accepted session, separate confidence in repeatability from validated measurement accuracy; require independent evidence for health claims.
- Acceptance check: Five nominal sessions plus one unrelated perturbed row must not upgrade to verified health.

### R05 — High: Capacity estimator ignores intra-session direction reversals

- Classification: Confirmed logic defect.
- Location: [BatteryCapacityEstimator.kt:39](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryCapacityEstimator.kt#L39).
- Evidence/impact: Flush checks only first/last endpoints; intervening SOC or counter reversals/reset can still pass the 12-point and range checks. FULL may extend a charging segment and the capacity minimum interval is 1 ms versus history's 1 s.
- Proposed correction: Split/reset on unsupported counter discontinuities, classify reversals, use one shared continuity policy; separate FULL endpoint from idle.
- Acceptance check: Synthetic resets and reversals, duplicate/near-duplicate time, FULL idle and valid partial sessions.

### R06 — High: Counter delta accepts implausible jumps

- Classification: Confirmed logic defect.
- Location: [CounterRateAccumulator.kt:14](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/CounterRateAccumulator.kt#L14); [BatteryRecorderService.kt:895](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L895).
- Evidence/impact: No physical magnitude or reset quality check beyond nonnegative endpoints/direction; 45,000 µAh in 30 s produces 5,400 mA. Historical RMX2161 counter is quantized in exactly that increment, so short interval rates cannot be treated as precise instantaneous current.
- Proposed correction: Report quantization, robust longer-window aggregate and interval quality; exclude reset/outliers; use checked accumulation.
- Acceptance check: Quantized, reset, missing, signed zero, normal and extreme counter fixtures.

### R07 — High: History/health windows can include future-dated records

- Classification: Confirmed logic defect.
- Location: [BatteryRecorderService.kt:558](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L558); [BatteryRecorderService.kt:798](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L798); [BatteryRecorderService.kt:841](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L841).
- Evidence/impact: Several queries use timestamp_ms >= from without <= now. A clock moved forward then corrected, or a future-dated import, pollutes 24h/7d/90d/etc. The latest percent is selected by ID without freshness or now constraint.
- Proposed correction: Use explicit [from,now] and one consistent snapshot; retain clock-anomalous records separately; label stale latest reading.
- Acceptance check: Future-dated row excluded from rolling window; clock rollback and imported earlier history tested.

### R08 — High: Threshold forecast has inconsistent origin

- Classification: Confirmed logic defect.
- Location: [BatteryRecorderService.kt:1126](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L1126).
- Evidence/impact: Heading promises time from first session sample. Observed thresholds use elapsed-from-start; predicted thresholds use remaining time from current sample. Example: 60→70% in 30 min, 80% forecast displayed 30 min under start-relative heading (60 min total).
- Proposed correction: Show observed elapsed from session start and projected remaining time separately or add elapsed to start-relative prediction.
- Acceptance check: Known 30+30=60 minute fixture and already-reached case.

### R09 — High: Continuity dashboard cannot prove current coverage

- Classification: Confirmed limitation.
- Location: [SamplingGapAccumulator.kt:16](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/SamplingGapAccumulator.kt#L16); [BatteryRecorderService.kt:841](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L841).
- Evidence/impact: Only positive intervals between stored rows are measured; gaps before first and after last are invisible, short missed samples may not count, and negative/discontinuous time is discarded. Zero gaps is possible with one old sample.
- Proposed correction: Report last committed sample age, expected vs observed coverage, leading/trailing gaps and invalid intervals; distinguish not monitored from missing.
- Acceptance check: One old row, no rows, reboot, clock rollback, 60/90/91 s gaps, recorder stopped.

### R10 — High: Recorder cadence drifts and snapshots are not simultaneous

- Classification: Confirmed behavior.
- Location: [BatteryRecorderService.kt:79](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L79); [BatteryRecorderService.kt:211](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L211).
- Evidence/impact: Sampler schedules next execution 30 s after completion rather than a monotonic fixed cadence. Row timestamps are captured before property reads, UsageStats and hardware inventory; late resource/USB readings may correspond to another time.
- Proposed correction: Use monotonic deadlines without catch-up storms; persist source acquisition start/end and actual latency; split slow inventory from battery sampling.
- Acceptance check: Slow collector, screen-off and delayed-worker tests; timing and skipped deadlines visible.

### R11 — High: Storage write failure can look like active recording

- Classification: Confirmed observability defect.
- Location: [BatteryRecorderService.kt:438](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L438); [BatteryRecorderService.kt:467](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L467).
- Evidence/impact: Sample insert exceptions only attempt an event write in the same database; both can fail silently (full/corrupt/locked DB). KEY_ACTIVE remains true and foreground notification says recording.
- Proposed correction: Persist last successful commit and error independently; report degraded/stopped status with bounded retry; never silently delete history.
- Acceptance check: Fault injection for disk full, lock timeout, corrupt schema and I/O failure.

### R12 — High: History reads, restore and recorder writes contend

- Classification: Confirmed design risk.
- Location: [BatteryRecorderService.kt:529](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L529); [BatteryRecorderService.kt:1233](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L1233); [MainActivity.kt:688](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L688).
- Evidence/impact: No explicit WAL configuration; separate SQLiteOpenHelper instances and concurrent long transactions. Restore inserts and verifies all rows in a single transaction, so recorder may block or miss observations. No stress or concurrent restore tests.
- Proposed correction: Benchmark WAL and bounded work; prevalidate checksum/schema outside writer transaction; preserve transactional restore and surface contention/gaps.
- Acceptance check: Concurrent recording/export/restore; low disk, killed process, multi-day history; no lost committed data.

### R13 — High: Unbounded retention and repeated large scans

- Classification: Confirmed scale risk.
- Location: [BatteryRecorderService.kt:678](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L678); [MainActivity.kt:688](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L688).
- Evidence/impact: Ideal cadence yields 1,051,200 samples/year and 262,800 inventory snapshots/year. Six rolling scans plus a 90-day capacity scan can visit ~2.73M rows per refresh at one year, plus 86,400 recent rows materialized and session lists; forced refresh on resume; no disk budget/benchmark.
- Proposed correction: Use incremental aggregates, bounded session queries, indexes aligned to ordering, lifecycle-aware refresh and storage warnings; retention only with user authorization.
- Acceptance check: 1-day/30-day/1-year fixtures; p95 query latency, heap and disk measured.

### R14 — High: Backup/CSV imports validate types, not semantic domains

- Classification: Confirmed validation gap.
- Location: [HistoryBackup.kt:47](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/HistoryBackup.kt#L47); [LegacyHistoryCsv.kt:16](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/LegacyHistoryCsv.kt#L16).
- Evidence/impact: Accepts SOC 999, screen_on=2, negative elapsed, invalid boot count, arbitrary state strings and absurd counters when parsable. Later code coerces or narrows fields and computes analytics.
- Proposed correction: Validate per-column domains, enforce bounded integer conversion, distinguish unknown from invalid; preserve rejected raw rows in explicit quarantine if needed.
- Acceptance check: Malformed but checksummed backup and valid-typed invalid CSV fail transactionally or are quarantined.

### R15 — High: Same-device backup origin is not enforceable

- Classification: Confirmed limitation.
- Location: [HistoryBackup.kt:10](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/HistoryBackup.kt#L10); [MainActivity.kt:842](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L842).
- Evidence/impact: Format contains no device/installation provenance or schema fingerprint beyond format version. SHA-256 detects corruption but not authenticity, device mismatch or deliberate modification.
- Proposed correction: Use non-identifying export provenance and explicit trust/confirmation; identify format/schema and source clock; never collect persistent hardware identifiers just for this check.
- Acceptance check: Cross-device and altered-but-rehashed backup identified or clearly disclosed.

### R16 — High: Restore supports earlier-only nonoverlap, not general recovery

- Classification: Confirmed functional gap.
- Location: [BatteryRecorderService.kt:1233](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L1233); [HistoryBackup.kt:139](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/HistoryBackup.kt#L139).
- Evidence/impact: Every imported timestamp must be strictly earlier than earliest existing event/sample. Overlapping incremental backups, later-only recovery and duplicate reconciliation are unsupported by design; early service events may block otherwise useful import.
- Proposed correction: Keep no-overwrite default; design explicit deduplicated merge only after identity and collision rules are defined. Preserve lossless backup and existing app data.
- Acceptance check: Earlier, empty, exact duplicate, partial overlap and newer-history fixtures; explicit result for each.

### R17 — High: Debuggable binary and local usage history exposure

- Classification: Confirmed privacy/release limitation.
- Location: Decoded APK manifest; [MainActivity.kt:731](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L731).
- Evidence/impact: Debug APK permits debugging; application captures sensitive foreground package history and exports raw records. Backup warning exists, but CSV export has no equivalent privacy prompt. Destination may be user-selected cloud storage; no encryption.
- Proposed correction: Use non-debug release; provide CSV disclosure, optional encrypted portable backup and explicit user-controlled history/export deletion with confirmation.
- Acceptance check: Non-debug manifest, no INTERNET permission, consent and destination behavior, recovery controls tested.

### R18 — High: App-usage history import is incomplete

- Classification: Confirmed architecture gap.
- Location: [BatteryRecorderService.kt:371](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L371).
- Evidence/impact: Only in-memory lastUsageQueryMs, first query past 60s, no persisted watermark/dedup or Android aggregate import. Process death and delayed events lose history or can duplicate transitions; multi-window/activity state remains approximate.
- Proposed correction: Persist import watermark with bounded overlap and dedup keys, handle event expiry and screen/app transitions, import Android aggregates separately.
- Acceptance check: Restart, permission revoke/regrant, late events, app switch, split-screen and long gap fixtures.

### R19 — High: Per-app network/storage/inventory and device-wide history incomplete

- Classification: Confirmed architecture gap.
- Location: [DeviceTelemetry.kt:11](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/DeviceTelemetry.kt#L11); DEVICE_TELEMETRY_ARCHITECTURE.md sections 2–7.
- Evidence/impact: TrafficStats is device/mobile/own UID only; no NetworkStatsManager, StorageStatsManager, visible-app inventory, usage aggregates, connectivity/display callbacks, full sensor metadata or utilization collector. No capability registry/import ledger. Do not claim unavailable privileged stats can be obtained.
- Proposed correction: Implement public/user-granted APIs in tested phases, explicit unavailable statuses; keep per-app electrical attribution unavailable.
- Acceptance check: Capability matrix per source, permissions denied/granted, on-device census and source-labelled history exports.

### R20 — High: CI covers only isolated database behavior on Android

- Classification: Confirmed test gap.
- Location: RecorderDatabaseAndroidTest.kt; [CI run](https://github.com/astramark06-sudo/Battery-only/actions/runs/45468739015).
- Evidence/impact: Four instrumentation tests exercise DB migration/backup/rollback/CSV. No UI, service, boot, permission, real-device, storage-pressure, long-history or end-to-end upgrade tests. No code coverage report.
- Proposed correction: Add lifecycle/UI/integration/upgrade and performance tests; test API 26, 32, 33, 35 plus target handset.
- Acceptance check: All gates evidenced with test reports; coverage measured, not inferred from passing tests.

### R21 — Medium: Stale preference can misrepresent service liveness

- Classification: Confirmed logic defect.
- Location: [MainActivity.kt:614](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L614); [BatteryRecorderService.kt:97](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L97).
- Evidence/impact: KEY_ACTIVE true is persisted, not a heartbeat. Process kill may leave it true; UI explicitly reads that flag after 1.2 s. Wake-lock status is also last reported only.
- Proposed correction: Use process-local service state plus persisted heartbeat with freshness and explicit unknown; separate configured mode from actual state.
- Acceptance check: Force-stop, task removal, service kill, startup delay and wake-lock failure.

### R22 — Medium: Operational feedback overwritten by live updates

- Classification: Confirmed logic/UI defect.
- Location: [MainActivity.kt:445](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L445); [MainActivity.kt:640](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L640).
- Evidence/impact: statusText is updated by 2-second dashboard polls; startup, export/restore results and errors disappear. toggleSamplingMode overwrites its own caught reconfiguration failure with success-like mode text.
- Proposed correction: Separate persistent telemetry state, progress and operation result areas; distinguish requested versus applied mode.
- Acceptance check: Export/restore/failure status persists through live refresh and rotation.

### R23 — Medium: Activity recreation does not preserve in-flight operations

- Classification: Confirmed UI/lifecycle risk.
- Location: [MainActivity.kt:85](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L85); [MainActivity.kt:731](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L731); [MainActivity.kt:860](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L860).
- Evidence/impact: Only pending picker filename saved. Raw Threads capture Activity and may outlive it; generation is deleted if Activity destroyed, copy/restore state resets and new instances can initiate concurrent operations.
- Proposed correction: Use application-scoped operation coordinator or lifecycle-aware state, cancellation and explicit recovery; ensure raw DB untouched.
- Acceptance check: Rotate/background/recreate during export generation, picker, copy and restore.

### R24 — Medium: Export durability and recovery incomplete

- Classification: Confirmed limitation.
- Location: [MainActivity.kt:776](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L776); [MainActivity.kt:796](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L796).
- Evidence/impact: Destination is considered successful after close without readback/checksum. Failed staging retained but no retry/cleanup UI; abrupt process death may leave partial destination or orphan staging files.
- Proposed correction: Track operation ID and expected digest; verify where provider supports it; retain and expose retry/delete controls for staging only.
- Acceptance check: Failing SAF provider, partial copy, cancellation, process death, duplicate filenames.

### R25 — Medium: Wake lock and scan overhead unmeasured

- Classification: Confirmed resource risk.
- Location: [BatteryRecorderService.kt:161](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L161); [DeviceTelemetry.kt:76](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/DeviceTelemetry.kt#L76).
- Evidence/impact: Continuous mode defaults to indefinite partial wake lock; cached scanner runs under synchronized lock. This app can alter the battery behavior it measures. No benchmark or acquisition latency budget.
- Proposed correction: Measure low-impact vs continuous overhead under normal use; clearly disclose and quantify; respect OS stop/power policies.
- Acceptance check: Screen-off test in both modes, CPU time, energy overhead, actual cadence.

### R26 — Medium: Kernel inventory bound is not a time or listing-memory bound

- Classification: Confirmed limitation.
- Location: [KernelInventory.kt:81](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/KernelInventory.kt#L81).
- Evidence/impact: Reads bounded bytes and caps visited entries/fields, but listFiles/sort may allocate full directory and file read has no deadline. Shared global cap can starve later roots; synchronized cache serializes callers.
- Proposed correction: Per-root budgets, elapsed deadline/worker isolation, precise partial status and acquisition time; test inaccessible/slow nodes.
- Acceptance check: Huge directory, alias cycles, denied roots, cap exhaustion, simulated slow reads.

### R27 — Medium: Cached USB candidates and gauge values lack visible per-reading freshness

- Classification: Confirmed provenance gap.
- Location: [BatteryRecorderService.kt:419](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L419); [BatteryRecorderService.kt:558](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L558); [MainActivity.kt:276](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L276).
- Evidence/impact: Same-supply online guard is good, but cached snapshot may predate plug change; battery row can carry USB candidate from prior state. Latest gauge report has no explicit acquisition age; stored root-prefixed paths are logical, not canonical physical paths.
- Proposed correction: Associate candidate with exact source and capture timestamp; invalidate on plug changes; label stale/ambiguous and never equate to calibrated input watts.
- Acceptance check: Plug/unplug during cache age; stale gauge; multiple USB supplies; provenance retained.

### R28 — Medium: Kernel field grouping and unit annotations are misleading

- Classification: Confirmed presentation defect.
- Location: [MainActivity.kt:473](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L473); [MainActivity.kt:574](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L574).
- Evidence/impact: External power panel includes thermal, CPU, devfreq and USB-device roots because filter only excludes battery/bms/main/@. Diagnostic path classification differs; some vendor values receive generic conversions or boolean labels without verified mapping.
- Proposed correction: Group by root/device type; annotate only verified unit contracts; display raw vendor state separately.
- Acceptance check: Fixture with CPU/thermal/devfreq/vendor and multiple power supplies.

### R29 — Medium: Raw battery metadata not fully preserved

- Classification: Confirmed data loss.
- Location: [BatteryRecorderService.kt:211](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L211); [MainActivity.kt:364](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L364).
- Evidence/impact: Negative/zero voltage discarded; temperatures below 0°C discarded; level/scale raw pair and unknown numeric state/health enums not stored. No per-sample quality/source time fields; transition events cannot uniquely reconstruct every sample.
- Proposed correction: Store raw extras with presence/type, separate normalized values and quality fields; migrate non-destructively; preserve legitimate signed temperature.
- Acceptance check: Missing vs zero vs negative, unknown enum and wrong type tests.

### R30 — Medium: Gap and screen/app attribution confidence overstated by endpoint-only observations

- Classification: Confirmed logic issue.
- Location: [RecorderTimeMath.kt:10](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/RecorderTimeMath.kt#L10); [BatteryRecorderService.kt:878](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L878).
- Evidence/impact: Unknown boot identity can allow rate intervals across actual reboots; same screen and package endpoints do not prove continuous same state between them. Usage Access column not used when computing attribution. Missing imported package states can be coerced.
- Proposed correction: Prefer known boot and source events; downgrade confidence when continuity not proven; use access status and explicit coverage.
- Acceptance check: Unknown boot/reboot, intervening screen/app transition and permission change fixtures.

### R31 — Medium: Session history messages and forecasts misstate missing data

- Classification: Confirmed UI logic defect.
- Location: [BatteryRecorderService.kt:995](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L995); [BatteryRecorderService.kt:1158](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L1158).
- Evidence/impact: Multiple samples with null endpoint percent reported as 'one sample only'; global forecast with zero remaining returns 'not enough matching history' rather than already at threshold; raw epoch-ms labels hinder use.
- Proposed correction: Separate sample-count, missing endpoint, reached threshold and insufficient coverage conditions; display local timestamp plus explicit timezone.
- Acceptance check: Null endpoints, one row, 0%/100%, mixed direction and valid threshold cases.

### R32 — Medium: CSV chronological export loses original sequence under clock rollback

- Classification: Confirmed interoperability limitation.
- Location: [BatteryRecorderService.kt:1276](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L1276); [LegacyHistoryCsv.kt:26](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/LegacyHistoryCsv.kt#L26).
- Evidence/impact: CSV ordered by wall timestamp, not ID/monotonic sequence. Legacy restore assigns IDs in CSV order; after rollback original ordering is not recoverable from CSV alone. Export flattens text and formula-protects strings (documented loss).
- Proposed correction: Keep portable backup as authoritative; record source sequence/boot metadata in a versioned export or warn about ambiguous order.
- Acceptance check: Clock rollback, repeated timestamps, multiline text and CSV roundtrip.

### R33 — Medium: Resource history only 24h with partial status handling

- Classification: Confirmed limitation.
- Location: [BatteryRecorderService.kt:640](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L640); [ResourceHistory.kt:43](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/ResourceHistory.kt#L43).
- Evidence/impact: Observed range and traffic coverage are useful, but no 7d/30d/90d/all-time resource summaries; section AVAILABLE can coexist with unsupported individual fields, and snapshot count includes all valid timestamps even if values absent.
- Proposed correction: Per-field quality and coverage, rolling resource windows, source-level status, historical import where supported.
- Acceptance check: Missing counters, duplicated snapshots, resets, reboot and coverage across windows.

### R34 — Medium: Android 15 edge-to-edge insets missing

- Classification: Confirmed compatibility gap.
- Location: [MainActivity.kt:124](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L124); targetSdk 35.
- Evidence/impact: Root uses fixed pixel padding without WindowInsets handling. Android 15 enforces edge-to-edge for target 35; content may be obscured. Actual appearance not tested.
- Proposed correction: Apply safe insets, dp spacing, adaptable layouts, font scale and TalkBack tests.
- Acceptance check: API 35 gesture/three-button navigation, cutout, landscape and large text.

### R35 — Medium: No accessible data-management or capability dashboard

- Classification: Confirmed UX/product gap.
- Location: [MainActivity.kt:124](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L124).
- Evidence/impact: Single long scroll with dense 10–12sp text, hardcoded English, no charts/filter/search or persistent operation status; no explicit history size/maintenance/export retry or per-source permission controls. Target-specific RMX2161 heading appears on any device.
- Proposed correction: Prioritize measurement summary, drill-down raw data, source/freshness labels and accessible controls; keep automatic recorder requirement and OS stop controls.
- Acceptance check: TalkBack, large font, rotation, low memory, explicit device model and user-controlled exports.

### R36 — Medium: CI pinning and reproducibility incomplete

- Classification: Confirmed build/release gap.
- Location: [Android workflow](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/.github/workflows/batteryscope-android.yml); Gradle settings.
- Evidence/impact: Some actions are tag-pinned not SHA-pinned, ubuntu-latest floats, no checked-in Gradle wrapper/dependency locks/verification metadata, lint/SBOM/dependency scan not run, ephemeral debug signer.
- Proposed correction: Pin verified actions/runner/toolchain, wrapper/checksums, dependency verification and release workflow; add lint and reproducibility checks.
- Acceptance check: Fresh builds from same source; verify dependency graph, permissions, signing and release artifact provenance.

### R37 — Medium: CI APK verification does not enforce full release identity

- Classification: Confirmed verification gap.
- Location: Android workflow; tools/verify_apk_update.py.
- Evidence/impact: CI checks package/versionCode and verifies any signing certificate, but does not enforce expected signer or versionName; preflight is separate synthetic policy suite. No release gate blocks debug artifacts automatically.
- Proposed correction: Add separate fail-closed release identity check (trusted public certificate only), keep development builds labeled, never store private signing material in repository.
- Acceptance check: Wrong package/version/name/certificate, debuggable, downgrade and known-good release cases.

### R38 — Medium: Long import limits can exhaust temporary storage

- Classification: Confirmed validation gap.
- Location: [HistoryBackup.kt:11](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/HistoryBackup.kt#L11); [MainActivity.kt:860](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L860).
- Evidence/impact: Staging allows 1 GiB, then DB transaction inserts before checksum verified; no free-space estimate. Per-field 1MiB limit can still create a row larger than Android cursor-window capacity when multiple fields are large. Legacy parser allows large character counts independent of byte budget.
- Proposed correction: Validate total/row-byte budget, free space and checksum before mutation; report progress, cancellation and transactional rollback.
- Acceptance check: Low space, oversized row, malformed UTF-8, checksum failure and cancellation tests.

### R39 — Low: Documentation and handoff checkpoints disagree

- Classification: Confirmed maintenance gap.
- Location: PROJECT_STATUS.md; DEVELOPMENT_HANDOFF.md; README.md; RELEASE_CHECKLIST.md.
- Evidence/impact: Older sections still say v4/main or collectors not implemented, newer source is schema v5 and draft PR #3. Historical passages are useful but not authoritative current status; CI pending text stale.
- Proposed correction: Create one authoritative current status and label historical sections; keep release checklist tied to exact commit.
- Acceptance check: Every current claim agrees with source and artifact; prior records clearly historical.

### R40 — Low: Large classes and duplicated mapping/validation

- Classification: Confirmed maintainability gap.
- Location: [MainActivity.kt:30](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/MainActivity.kt#L30); [BatteryRecorderService.kt:41](https://github.com/astramark06-sudo/Battery-only/blob/17c61bdfaa23b24860d32598f9ea1d245c975710/BatteryScope/app/src/main/java/com/batteryscope/BatteryRecorderService.kt#L41).
- Evidence/impact: Activity ~901 lines, service/database ~1,315 lines; duplicate broadcast parsing and status/plug formatting; dead csvEscape helper. Divergent mapping and UI/service validation can drift.
- Proposed correction: Extract typed telemetry source, repository, analytics, export coordinator, UI state and shared enum parsing with regression tests.
- Acceptance check: One shared parsing/validation contract; no behavior regression in existing tests.

## Capability reconciliation

| Capability | Source status | Verification |
|---|---|---|
| Framework battery status/SOC/current/voltage/counter/temperature | Implemented, quality incomplete | arithmetic tests; real firmware units unresolved |
| Battery-side power | Computation implemented, guarded | unit tests; target readings invalid/unverified |
| Charger-input V/A/W, SuperDart contract | Conditional same-supply raw candidates only | no calibrated device evidence |
| Empirical and kernel gauge health | Implemented estimates | unit tests; nominal counter warning insufficient, no calibrated health |
| Automatic foreground service/boot restart | Implemented | compile only; no lifecycle/device tests |
| Local SQLite v5 | Implemented | JVM v1–4 migration, emulator v4 migration and duplicate timestamps |
| Portable backup and earlier-history restore | Implemented with limits | typed/rollback emulator tests; no large concurrent restore or true installed upgrade |
| Legacy CSV | Implemented with documented loss | JVM/emulator tests; sequence and semantic validation gaps |
| UsageStats foreground correlation | Partial | logic tests only; no historical aggregate import/permission lifecycle |
| RAM/storage/thermal/power/sensor inventory | Partial | source implemented; actual hardware visibility and overhead unknown |
| Device/mobile/own UID traffic | Partial | resource accumulator tests; no real runtime verification |
| NetworkStatsManager per-UID Wi-Fi/mobile buckets | Not implemented | legitimate API scope must be tested |
| StorageStatsManager per-package storage | Not implemented | Usage Access/device scope must be tested |
| Installed-app inventory and package change history | Not implemented | package visibility applies |
| CPU/GPU utilization | Not implemented; optional frequency fields only | no validated device access |
| Display/connectivity and thermal change history | Not implemented beyond interactive/periodic status | no device tests |
| Complete capability registry/import ledger | Not implemented | per-field/source/time model needed |
| Multi-window battery history | Implemented, issues above | no end-to-end analytics or performance test |
| Device-wide long-term history | Partial 24h resource history | long-window analytics absent |
| Privileged BatteryStats/per-UID electrical energy | Not supported for ordinary app | remain unavailable; no workaround |
| Final release/update | Blocked | signer mismatch, device tests missing |

## Independent edge-case checks

- Recomputed 3.85 V × 2147.483647 A = 8267.81204095 W from accepted sign-only input (source logic, not a handset reading).
- Recomputed 45,000 µAh / 30 s = 5,400 mA: a quantized counter step is not precise instantaneous electrical current.
- In-memory SQLite fixture with timestamps 1000, 2000, 1000000: query `timestamp_ms>=1000` returned the future row even when reference now=2000, confirming the lower-bound-only predicate issue.
- Start-relative threshold example: 60→70% in 30 min; 80% at same rate is another 30 min, 60 min from session start. Source labels the latter projection as start-relative but emits remaining duration.
- Ideal 30-second recording yields 2,880 samples/day, 86,400 per 30 days, 1,051,200/year; inventory every 120 s yields 262,800/year. These are theoretical maxima at uninterrupted target cadence, not measured performance. Six summary windows and capacity scan over one year amount to about 2,730,240 row visits before recent-session materialization and other queries; actual query plan, latency and I/O must be benchmarked.

## Strengths to retain

- Local-first design; decoded APK manifest has six narrowly scoped permissions (foreground service, special-use service, notifications, usage access, wake lock, boot receiver), no INTERNET; no production runtime dependency declared other than Kotlin/Android platform.
- Recorder and boot receiver are not exported; notification PendingIntent is immutable; OS backup disabled; no root/Shizuku or sysfs write path in reviewed production code.
- Uses same-supply gauge pairs and USB online/identity guard; separates battery-side watts from input power; missing values often remain null; no guessed firmware unit conversion.
- Non-destructive v5 schema migration with transaction and row-count check, duplicate wall timestamps preserved; explicit backup checksum, rollback and no-overwrite restore; fixed 28-column spreadsheet-safe export.
- Raw inventory allowlist, path boundary, bounded field prefix and no arbitrary shell execution; shared cache; off-main-thread heavy processing under normal paths.
- Passing tests are real evidence: keep all 64 existing tests as regression gates.

## Required execution order and acceptance gates

1. **P0 release safety:** establish installed certificate/version from a trusted baseline, determine privately whether original key exists; verify data-preserving update path. Do not uninstall, upload private keys/passwords, or distribute this debug build as an update. Keep PR draft.
2. **P1 correctness:** R03–R10/R14/R29–R32. Add shared typed measurement/quality and continuity contracts, explicit clock bounds, corrected threshold/coverage reporting, semantic import validation, and regression tests before changing release metadata. Do not guess device-specific units.
3. **P1 recorder and durability:** R11–R13/R15–R17/R21–R27/R38. Fault-injection and concurrent transaction tests, persisted monitoring health, export/restore recovery, storage/overhead benchmarks; preserve raw DB.
4. **P2 scope:** R18/R19/R33. Implement only legitimately accessible system aggregates and device capabilities; distinguish unsupported privileged sources. Develop one tested collector at a time and use capability/source quality ledger.
5. **P2 UI and build:** R22/R23/R28/R34–R37/R39/R40. Accessibility, Android versions, non-debug release gate, reproducible dependency and toolchain policy, consistent docs.
6. **Device gate:** on RMX2161 Android 12, test actual first launch, update retention, permissions, normal charging/discharging, 24+ hour screen-off and low-impact/continuous recording, reboot, OS stop, multi-day/long-history performance, export and recovery, actual hardware inventory. Use safe normal use; do not force battery to 0% or manipulate charging controls. Record observations, not assumed measurements.

## Explicit limits

No physical RMX2161 access, trusted installed APK certificate or original key; cannot verify installed-data migration, current/voltage calibration, hardware visibility, 24/7 continuity or real overhead. No fresh Gradle/lint run, DEX decompilation, dynamic security test, CVE audit, accessibility rendering, test coverage measurement or long-term performance benchmark in this turn. Existing CI tests and exact downloaded binaries were independently examined as described, not rerun. All 64 paths were inventoried; static inspection is not proof of absence of other defects. This report authorizes neither a merge nor an installation. The task was an audit, so only documentation should be changed.

## External platform references

- Android BatteryManager: https://developer.android.com/reference/android/os/BatteryManager.html
- Android 15 Views edge-to-edge: https://developer.android.com/develop/ui/views/layout/edge-to-edge
- Android foreground service user stop: https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- Android TrafficStats: https://developer.android.com/reference/android/net/TrafficStats
