# BatteryScope — persistent development handoff

## 2026-11-25 — Fix counter-duration regression from boot policy

- Cumulative run [45480551635](https://github.com/astramark06-sudo/Battery-only/actions/runs/45480551635) failed four CounterRateAccumulatorTest methods: duration-only plausibility called the newly strict boot-aware method with null boot counts. Split numerical duration validation from known-boot eligibility. The history and session callers still enforce boot continuity. Added explicit regression for valid duration/counter delta with no boot context. New CI required; previous run must not be treated as passing.

## 2026-11-25 — Conservative boot and permission-aware endpoint correlation (R30 partial)

- R30: rate eligibility now requires a nonnegative known matching boot count at both endpoints. Older unknown-boot records remain in raw storage/exports and gap counts, but are excluded from rate/session estimates. Summary counts unknown-identity intervals explicitly. App correlation now additionally requires recorded Usage Access at both endpoints; live recorder checks permission before and after the usage query and does not store a package when access is unavailable.
- Labels clarify that matching screen/app endpoints do not prove uninterrupted state between observations. Exact event-based reconstruction, durable usage watermark and permission lifecycle/device tests remain pending (R18/R30).
- Independent Kotlin harness passed 459 checks of exact RecorderTimeMath helper (blob 20ef864be7951aaaee67263445aed7cdc5d6fd59), covering boot/duration boundaries, screen/access/package combinations and prior clock rules. Updated JVM tests and disposable Android regression for raw history retained, unknown-boot exclusion and denied-access correlation. Latest cumulative Android CI required. No schema, version, signing, permissions or existing-history changes; keep draft.
 
## 2026-11-25 — Root-aware kernel presentation, candidate freshness and display compatibility (R27/R28/R34/R35 partial)

- Prior staging recovery head `a3247fa7e2f2969b86a11be0597bdacae394209d`: Android build/JVM and emulator instrumentation test steps succeeded in [run 45479251876](https://github.com/astramark06-sudo/Battery-only/actions/runs/45479251876); signing policy succeeded.
- Dashboard now keeps full inventory root prefixes, groups supply/battery, role, thermal, compute and USB-device data separately, labels vendor values raw, and labels standard-unit conversions conditional. No vendor enum inference or live USB wattage claim. Shared USB candidate assessment requires known acquisition-start age, recent inventory, wired framework plug, one plausible same-supply online USB pair, and rejects ambiguity. Stored inventory metadata includes candidate quality and source; old raw fields remain intact. This is a conservative cached correlation, not proof of simultaneous acquisition or calibrated charging input. Gauge report displays historical acquisition time and apparent wall-clock age.
- Android 15+ root applies system-bar and display-cutout insets, without adding those insets to earlier fitted layouts. Density-independent padding, larger section labels/body text, accessibility headings on API 28+, full-width history buttons and actual Build.MODEL/OS subtitle. Based on https://developer.android.com/develop/ui/views/layout/edge-to-edge. API 35 visual verification, large-font and TalkBack tests remain required; no full dashboard redesign.
- Added two JVM tests for root collision, raw-only fields, stale/missing capture, incompatible plug, unique/ambiguous candidates. New Android CI required. No schema/version/signing/permissions changes, no history deletion or charging-control writes. PR stays draft.

## 2026-11-25 — Explicit staging recovery and Android transfer regression (R24 follow-up)

- Prior head `d85c12ad428b34c0e7039ee3385ba72ce23b7af4` passed [Android CI](https://github.com/astramark06-sudo/Battery-only/actions/runs/45478239043) and signing-policy CI. Latest changes distinguish exact atomically published UUID filenames, older directly-written copies of unknown completeness, and interrupted .part files. No staging files are automatically deleted by discovery.
- Older copies require an explicit recovery warning, may be copied byte-for-byte but never claim complete history or auto-delete after copy. Interrupted files cannot be exported as completed backups; selected discard requires confirmation and affects only a recognized private staging file. Exact name and canonical-file checks prevent redirected or unrelated files being used.
- The operation coordinator is instantiable with a private test directory while the production singleton remains process-wide. New JVM directory/filename tests and a disposable Android integration test use actual ContentResolver file streams for failed save, retry/truncation, read-back, legacy recovery, interrupted rejection and discard. This does not test external SAF cloud providers, actual Activity recreation or crash durability.
- R24 remains partial until new CI and external provider tests. No history schema, signing, version, permissions or production history changes. PR stays draft.

## 2026-11-25 — Rotation-independent history work and verified export copies (R23/R24/R17 partial)

- Prior bounded-session checkpoint `3ad0f4a65093bde77d1e6fd6b493d097a427e20c` passed [Android build/JVM and emulator CI](https://github.com/astramark06-sudo/Battery-only/actions/runs/45477206169) and signing-policy tests.
- A single application-scoped history worker now owns export preparation, destination copy/read-back, discard and import. It retains operation state across Activity recreation without holding an Activity reference. A successful restore increments a process-local revision so the visible Activity refreshes history. The document picker retains its selected filename/request in saved instance state.
- Export is deliberately two-step: prepare a private completed file, then explicitly choose Save / retry prepared export. Completed files are published only after flush/sync and same-directory rename from .part, rediscovered after process restart, retained on cancellation, copy failure or unverifiable destination. Destination is opened for truncating write and reopened to compare byte count and SHA-256 before removal of the prepared copy. Explicit discard affects only a selected staging copy, never raw history. CSV now has a privacy disclosure as well as backup.
- Added three JVM tests for streaming SHA-256/copy, truncation/corruption, non-progressing streams and safe filenames. New CI pending. R23/R24 partial: actual Activity recreation/document-provider tests, process termination during write/restore, leftover .part cleanup and crash-durable operation results remain unverified. Read-back may be unavailable on some providers; copy is not reported verified in that case.
- No schema, permissions, application ID, signing, version or raw history changes. Earlier-only restore policy unchanged. Keep PR draft. Next: Android lifecycle/provider integration tests, export failure recovery and remaining measurement/provenance/telemetry findings.

## 2026-11-25 — Bounded recent-session processing and unavailable-result labels (R13/R31)

- Recent 30-day session query now streams rows directly into independent charging/discharging collectors, each retaining at most eight completed aggregate summaries and one active run. A run stores endpoints, valid elapsed time, screen/counter totals and first threshold crossings, not its entire sample list. The detailed latest-session and compact recent-session reports use the same collector. Original ID order, rate interval, FULL terminal endpoint and no catch-up/interpolation rules are preserved.
- Two disposable Android regressions cover ten sessions (only newest eight displayed, first FULL endpoint only, threshold timing) and missing endpoint SOC versus one-sample state. Historical forecast at an already reached threshold now reports that fact even when no valid rate exists. Partial R31: epoch-time presentation remains unchanged.
- Prior WAL/single-pass commit `ee85fe421b3613ad7c8606976a2f7244f9c87f2c` [Android workflow](https://github.com/astramark06-sudo/Battery-only/actions/runs/45476540196) build/JVM and emulator jobs succeeded; signing-policy workflow passed. New bounded-session changes require their own Android CI. Persistent incremental aggregates, writer contention and real handset benchmark remain unresolved. No schema, signing, version, permissions or history changes.

## 2026-11-25 — Database concurrency and one-pass rolling summaries (R12/R13)

- Prior head `3f4559a636bc6cb36de04cec5384745a6c1f2bba`: [Android CI](https://github.com/astramark06-sudo/Battery-only/actions/runs/45475629184) and [signing-policy CI](https://github.com/astramark06-sudo/Battery-only/actions/runs/45475629179) succeeded, including the asynchronous inventory and recorder deadline changes.
- All RecorderDatabase handles request WAL before opening. A disposable Android test holds an uncommitted writer transaction while a separate helper reads committed rows, then verifies the committed result. WAL allows readers and one writer to overlap; it does not permit multiple concurrent writers or guarantee timely restore/recording.
- Six rolling summaries now share a single ID-ordered cursor and decode each row once. Independent window accumulators preserve the original wall-time eligibility, per-window previous row, gap and rate calculations, including duplicate timestamps and clock rollback. Added a disposable regression fixture for boundary, future and out-of-order records. Recent-session and 24-hour resource queries explicitly use the existing timestamp index while retaining ORDER BY id. No index or schema migration.
- The previous desktop [synthetic benchmark](https://github.com/astramark06-sudo/Battery-only/pull/3#issuecomment-7142777427) motivates these changes; it is not a measurement of the new app on the handset. New CI pending. R12/R13 remain partial: writer-vs-writer contention, full report latency and memory on Android, unbounded history and incremental persistent aggregates still require work.
- No change to application ID, version, signing, permissions, backup/CSV format or retained history. Draft PR remains unmerged. Next: verify current CI, measure report latency on a large disposable fixture, reduce recent-session memory/long-range refresh overhead, then export and lifecycle hardening.

## 2026-11-25 — Asynchronous inventory and fixed recorder deadlines (R10/R11 follow-up)

- Preserved the newer `66e0cfacf9df12bc3b3ec54df89923c0127937b7` checkpoint, including its recorder health, operation feedback and import-validation changes. No overwrite or merge of the earlier competing implementation.
- R10: slow hardware inventory now runs on a separate HandlerThread. A bounded synchronized handoff permits only one outstanding or pending snapshot; an inventory must have completed before the battery sample starts, and is acknowledged only after successful database insertion. A failed insert retains the exact pending result. Cache acquisition start/end and request/association timestamps explicitly distinguish asynchronous or cached hardware/USB observations from battery sample time. No new database columns or permissions.
- Recorder maintains one elapsed-realtime sampling phase, counts skipped deadlines and does not generate catch-up bursts. A main-thread notification watchdog checks stale state every 15 seconds while the process can run; it does not issue duplicate notification updates. Handler scheduling remains subject to suspend and OS restrictions; UsageStats and SQLite latency can still delay samples. No guarantee of continuous sampling or simultaneous sensor readings.
- Added four JVM regression test methods (three inventory handoff and one fixed cadence). Independent local Kotlin compilation of exact new helpers matched Git blob hashes `27b8725924fa6d7ea810ae8f4700b7e658c7d859` and `29de809070f60522e2a1f030707dda7a0ce15ab1`; 8,042 assertions passed, including a deadline sweep, failed-insert retention, stale acknowledgement, retry cadence and health state. This is pure-helper verification, not a service, Android build, or physical timing test. New PR CI remains required.
- No schema, application ID, version, signing, history deletion or APK release changes. Next: verify CI for this exact head, R12/R13 database concurrency and long-history benchmarks, real device recorder timing and release signing.


## 2026-11-25 — Import integrity and recorder verification (R14/R38; partial R12)

- Recorder cadence/health helper at `470983562593dc2c667cdb3282c0b7b749df3356`: independently compiled exact Git-blob-matched Kotlin helper; 1,333 checks passed (including a duration sweep). Follow-up `8580b067e504b3b58276380265cb619ace8b29f8` prevents fatal errors being reported as successful commits and isolates preference write failures. Android CI for new source must be checked separately.
- Backup and legacy CSV imports reject invalid normalized fields (SOC, flags, boot count, status, plug type, timestamps and integer narrowing) and oversized combined rows. Raw signed telemetry is not normalized or discarded by import. Old malformed normalized rows may be rejected explicitly; raw export/history remains intact. Strict UTF-8 decoding for staged CSV.
- Private staged portable backup is verified fully before the DB write transaction, then re-read and checksum-verified before commit. Both import paths have a conservative free-space estimate (2x staged bytes + 16 MiB) before DB restore; this is not a guarantee. Earlier-only/no-overwrite policy remains unchanged. No schema or CSV format change. Added three JVM and two Android regression methods.
- R14 source fix; R38/R12 partial: prevalidation and bounds reduce risk but large-transaction contention, cancellation, real storage exhaustion and benchmarks remain unverified. No change to app ID, version, signing, permissions or existing history; PR remains draft. Next: database concurrency/scale benchmarks (R12/R13), storage recovery and physical testing.
 
## 2026-11-25 — Recorder cadence, commit health and operation feedback (R10/R11/R21/R22)

- Prior head `e0639c4dde3bb18ebb0ed1a9b168363ee178348f`: [Android CI](https://github.com/astramark06-sudo/Battery-only/actions/runs/45472621667) and [signing policy](https://github.com/astramark06-sudo/Battery-only/actions/runs/45472621774) passed, including emulator job. R03–R09 source changes are retained.
- New monotonic cadence compensates for work duration and skips missed slots instead of generating catch-up samples. Handler scheduling is still subject to Android sleep/OEM delays; slow collectors are not yet isolated or per-field acquisition-timestamped (R10 partial).
- Sample exceptions now reach process-local recorder health; successful commits alone advance the success timestamp. Best-effort private preferences retain historical attempt duration, last commit and sample/event failure, separately from SQLite. Notification distinguishes waiting, failed/retrying and last sample saved. Dashboard marks missing/stale process-local state; preferences alone no longer establish liveness (R11/R21 source fixes). Actual disk-full persistence, service death and notification behavior still need device/fault tests.
- Live readings and operation feedback now use separate text views; failed mode reconfiguration no longer overwritten by success-like copy, and requested mode differs from applied wake-lock state (R22 source fix). Rotation-safe operation state remains R23.
- Three JVM regression methods cover cadence, startup, staleness, failure and recovery. New Android CI pending. No schema, package, version, signing, permissions or history deletion changes. Draft remains unmerged. Next: R12/R13 database concurrency and scale; R14/R38 import validation.
 
## 2026-11-25 — Counter integrity and observation continuity (R06/R09)

- R06: remaining-charge readings and individual counter changes now pass broad rejection-only plausibility checks before interval rates and per-app correlations use them. The remaining-charge envelope is 0–12,000,000 µAh; the interval delta envelope uses a 20,000,000 µA sanity bound plus 45,000 µAh allowance for the previously observed coarse gauge steps. These are engineering rejection thresholds, not validated hardware limits or calibrated units. Missing/rejected intervals do not dilute denominators; checked additions prevent sum wraparound. Raw data remains unchanged. Session endpoint changes are explicitly distinguished from interval-validated rates, and accepted/classified duration and quantization warnings appear in history. Capacity rejects out-of-envelope counter readings; same-direction jumps inside the envelope and actual gauge calibration remain unresolved.
- R09: continuity reports empty windows, leading unobserved wall-time edges, latest eligible timestamp age, intervals at least twice the target, rate-ineligible/nonpositive intervals, known reboots and eligible adjacent interval duration. These are observations, not proof of uninterrupted monitoring or a continuous coverage percentage; reasons for gaps remain unknown.
- Three JVM regression methods and one disposable Android integration test added. Independent Kotlin harness recompiled exact changed pure Kotlin source files (matching Git blob hashes) and passed 52 cumulative assertions (17 additional checks), including earlier measurement/capacity regressions. Full Android compilation and instrumentation of this new revision remain pending.
- Prior commit `3a7908f60d1debd3cd26facfd5dfa254e8f9384c` completed [Android CI including emulator tests](https://github.com/astramark06-sudo/Battery-only/actions/runs/45471334893). Intermediate R03–R05 commit `22b93e5e79e0eb0fc340dfe20bc3c4b85dd6a427` had successful signing policy CI and Android CI running at inspection; a superseding PR run may cancel it. No merge, APK release, version, signing or schema change. Device validation remains required. Next: R10/R11 scheduling and recorder failure detection; R12/R13 concurrent database and scale tests.


## 2026-11-25 — Measurement and capacity quality corrections (R03–R05)

- R03: instantaneous BatteryManager current rejects signed integer/long sentinels and readings outside a broad ±20,000,000 µA rejection-only plausibility envelope. This engineering guard is not a calibrated device operating range; values inside it still require unit and polarity validation. Raw recorder values are retained, with no scale/sign guesses.
- R04: exact nominal charge-counter/SOC proportionality is now checked per accepted capacity session, including a constant offset. Any matching accepted session retains the LOW indicator, regardless of unrelated rows. All ratios to design are labelled unverified health; session repeatability is not calibrated accuracy.
- R05: capacity segments split at SOC/counter reversals, known reboot, state change or intervals outside the shared 1–90 s policy. FULL idle no longer extends a segment. This does not yet identify every same-direction counter jump; R06 remains open.
- Five JVM regression test methods added. Independent Kotlin harness compiled the exact proposed production files and their unchanged dependencies (Git blob hashes matched), and passed 35 assertions for boundary/sentinel rejection, nominal-session masking, reversals, FULL/short intervals, valid capacity, reboot, streaming and report semantics. Full PR Android CI and handset validation remain required.
- Baseline `3a7908f60d1debd3cd26facfd5dfa254e8f9384c`: [build and JVM job passed; emulator in progress at inspection](https://github.com/astramark06-sudo/Battery-only/actions/runs/45471334893). Draft PR #3 stays unmerged. No change to schema v5, application ID, version 1.15/16, signing or retained history.
- Next: R06 counter quantization/jump handling, R09 continuity coverage, R10/R11 cadence and recorder failure status, then long-history performance. Signing and device measurement remain critical release blockers.


## 2026-11-25 — Audit R07/R08 report corrections (CI pending)

Rolling history, session, resource, capacity and last-reading queries now use inclusive reference-time bounds plus fixed sample ID ranges captured once per report. Future-dated rows remain in raw storage/exports but cannot enter these summaries. Last level is labelled recorded (not live) and its age is displayed. Session threshold predictions explicitly show both total from session start and remaining from the last sample; observed crossings retain their start-relative meaning. Added disposable Android database tests for a retained future-dated sample and a 30-minute session with a further 30-minute forecast. R07/R08 source fixes implemented; emulator regression and physical-device verification pending. No schema, data deletion, signing or version changes. The prior refresh-gate commit is `b6041c4224363ed9a488a2ca59a4fd1a49c8a840`.


## 2026-11-25 — History refresh race correction (CI pending)

Active development: `fix/apk-update-preflight`, draft [PR #3](https://github.com/astramark06-sudo/Battery-only/pull/3). Previous verified head `17c61bdfaa23b24860d32598f9ea1d245c975710`: [Android build, JVM and emulator jobs](https://github.com/astramark06-sudo/Battery-only/actions/runs/45469977712) and [signing-policy tests](https://github.com/astramark06-sudo/Battery-only/actions/runs/45469977496) succeeded. The comprehensive [audit](FULL_APP_AUDIT_2026-11-25.md) was subsequently committed at `e60d2443382c6857fe1d0f4cd422d82914fdeb43`; this change preserves that checkpoint. Older main-branch and CI-pending records below are historical.

Fixed a UI race: a forced history refresh (including after restore) was previously dropped during an in-flight read, leaving stale history until the five-minute periodic refresh. Capacity refreshes could also be dropped. Main-thread RefreshGate now coalesces forced requests, schedules one immediate follow-up after the worker finishes, preserves the periodic cooldown and prevents overlapping workers for each report. Destroyed/finishing Activities do not launch follow-ups. Added three JVM regression tests for forced requests, cooldown and capacity admission. New commit build and actual Activity behavior are not yet verified; check PR CI and physical-device restore behavior.

App ID, versionCode 16/versionName 1.15, database schema v5, raw history and signing configuration are unchanged. Draft PR remains unmerged; signing continuity and physical RMX2161 validation still block release. Next development: address audit findings R03–R09 and benchmark long-history report queries while preserving interval semantics.


## 2026-11-25 — Verified CI recovery checkpoint

The active development branch is `fix/apk-update-preflight`, draft [PR #3](https://github.com/astramark06-sudo/Battery-only/pull/3). Head `54c9efa80a147c4093de5985cc8d24058373be05`; tested PR merge ref `cefed72488be9cd7aafcc013fa7fb6c26741e921`.

- [Android CI run 45468739015](https://github.com/astramark06-sudo/Battery-only/actions/runs/45468739015): both build/test/inspection and Android 12L emulator jobs succeeded. Four instrumentation tests ran and completed; JVM test task and both APK assemblies succeeded. The prior missing-AVD failure is resolved in this run.
- [Signing policy run 45468739077](https://github.com/astramark06-sudo/Battery-only/actions/runs/45468739077): eight synthetic policy tests passed.
- Debug APK artifact ID 14957875318, size 961,918 bytes (APK), package com.batteryscope, versionName 1.15, versionCode 16, v2 signature verified by CI. APK SHA-256 `a3e4980338ce0aff1ae97a5c77b4c71537d972d2d5bb0d54e658598d94172cd7`; certificate SHA-256 `c63b35c09c7ca7a8533a76b3d36cb19e1b09366d8dc90d05b9952031f6611df9`. ZIP digest `e2cbbba0ecc5829f989afb390106c6d5baee5ebe009154adf4e3ce142e5878d1`. Values taken from CI logs and artifact metadata, not an independent local artifact inspection.
- The certificate differs from the documented historical v1.14 and v1.15 certificates. The version code also remains 16, so this is not a verified release update. Keep PR draft; do not uninstall, merge or distribute as an update.
- Next release gate: independently identify the actually installed version and public signing certificate; determine whether its original signing key remains securely available. Never upload private keys or passwords to the repository or chat. Then run real candidate/baseline preflight and physical RMX2161 migration, measurement and recorder tests. Device-wide collector expansion and long-history benchmarking remain development work.

## 2026-11-25 — Android integration verification

Added CI-only Android 12L instrumentation job and isolated SQLiteOpenHelper tests for actual v4 migration, duplicate timestamps, portable backup round-trip, exact REAL and signed INTEGER preservation, corruption/overlap rollback, earlier-history ordering and legacy CSV import. The app itself does not require ADB or emulator setup. Backup reads numeric cursor values by their declared type rather than using platform-dependent string formatting. App ID, version and database schema unchanged; physical RMX2161 migration remains unverified. CI pending.

## 2026-11-25 — Shared inventory and resource telemetry

Unified dashboard and recorder hardware inventory via shared bounded read-only allowlist, full source paths in stored JSON, cached snapshots with acquisition timestamps. Added public Android RAM, internal storage, TrafficStats device/mobile/own UID cumulative bytes, power-save/idle/interactive/thermal status and headroom, processor count and sensor-type inventory. No extra permission, sensor streams, network transmission or schema/CSV changes. CPU/GPU frequency fields are raw optional sysfs only, not utilization. USB candidate requires online=1 and same power_supply source. Dashboard resource snapshot refreshes no faster than 60 s; recorder inventory cadence remains 120 s. Device support and monitoring overhead remain unverified. CI pending.

## 2026-11-24 — Legacy CSV history import

Added original, fully quoted 28-column CSV import for earlier exports from the same handset. Parser bounds fields/rows, validates schema and typed values, rejects malformed data, and validates before starting the transactional non-overlap restore. CSV is neither checksummed nor lossless; text transformations and historical null/empty ambiguity cannot be reversed. Do not edit or resave CSV in a spreadsheet before import. Portable backup cursor ownership hardened on failures. CI pending.

## 2026-11-24 — Portable history backup and restore

Portable .bsh v1 backup preserves typed nullable raw sample/event values in a checksummed stream, separate from the spreadsheet-safe CSV. It is not encrypted; use a private user-selected destination. Restore stages bounded input locally, inserts inside one transaction, verifies SHA-256 and counts before commit, and rejects overlapping history rather than overwriting. Earlier rows receive IDs before existing rows. Same-handset only; importing legacy CSV or migrating an already installed APK with an unavailable signing key is not solved by this feature. CSV and backup capture fixed sample/event ID bounds from one SQL statement. JVM codec tests added; Android restore/concurrency validation pending. CI pending.

## 2026-11-24 — Lossless sample identity migration

Schema v5 uses an independent autoincrement sample ID; repeated wall-clock timestamps can now coexist. Transactional v1-v4 upgrade copies all sample fields and checks the row count before replacing the old table; events are not rebuilt. Analytics use insertion order for newly recorded history; CSV retains wall-clock ordering and its 28-column schema. Added actual SQLite JVM migration, rollback, and duplicate-timestamp regression tests using a test-only JDBC dependency. Android handset migration, free-space behaviour and historical clock corrections still require device validation. The migration may temporarily need additional database space. CI pending.

## 2026-11-24 — Recorder lifecycle and streaming capacity

Capacity estimation now streams ordered SQLite rows, holding only active session endpoints, accepted session estimates and a small SOC-level set. This removes the 90-day in-memory sample list but SQLite sorting and all-time history scans still need benchmarking. Recorder reconfiguration also starts recording when a stale preference caused a fresh service instance. All start paths handle errors, disclose requested versus actually held wake lock, and preserve a startup diagnostic. Kernel scan cadence and history UI throttling use monotonic time. Capacity worker duplication is guarded. Android lifecycle/device validation remains pending. CI pending.

## 2026-11-24 — Export integrity and privacy

CSV header, sample and event schemas share one canonical 28-column definition with regression tests. Formula-like text is escaped for spreadsheet safety; signed numeric fields are unchanged. Export reports actual record count; header-only files are removed. Pending document-picker filename survives recreation, new staging files are private, and successful/cancelled staging copies are cleaned without deleting SQLite history or previous exports. Failed destination copies remain in app storage. Long-export snapshot isolation and process-death during generation remain outstanding. CI pending.

## 2026-11-24 — Session and fuel-gauge integrity

Session analytics now track valid counter duration separately, report mixed screen-state endpoints rather than attributing them, show signed session rates, and preserve observed threshold crossings. Kernel gauge fields must belong to one battery supply. Capacity estimates use monotonic timing where available and flag exact nominal-capacity/SOC proportionality as unverified health. Added focused regression tests. CI pending.

## 2026-11-24 — Analytics and metadata follow-up

Follow-up: rate denominators use only valid, direction-matching counter intervals (including zero); distinguish absent plug and battery-present metadata; recognize unplugged NOT_CHARGING for history and capacity; clear stale app attribution after permission/query failures; reject rather than overwrite wall-clock timestamp collisions (incoming sample still cannot be stored until a future schema migration). Raw units and signing remain unverified. New JVM tests added; CI pending.

## 2026-11-24 — Recorder interval integrity (proposed)

History analytics exclude intervals outside 1–90 seconds and intervals spanning a known reboot; unknown boot counts retain best-effort timing. Foreground-app correlation requires matching nonblank packages and interactive displays at both endpoints. Recent charging sessions no longer absorb a preceding FULL idle segment. Raw history, app identity and schema are unchanged. New JVM regression tests added; CI and physical-device validation pending.

> **Repository migration — 2026-11-24:** Current development is in [Battery-only/main](https://github.com/astramark06-sudo/Battery-only/tree/main). The former `batteryscope` branch in `ocr` is historical, not the active development branch. The [standalone Android build](https://github.com/astramark06-sudo/Battery-only/actions/runs/45348612049) passed for commit `45d65c6b624515bfd269e1eaa6380cb4611e050a` (artifact ID `14899402289`). Earlier source commits, checksums and signer records below remain historical evidence; no new device or signing-compatibility verification is claimed.

Last updated: 2026-11-23  
Repository: https://github.com/astramark06-sudo/Battery-only  
Active branch: `main`  
Target device: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted  
Current source version: 1.15 / versionCode 16  
Package: `com.batteryscope`

## Non-negotiable architecture

- Standalone Android app only.
- No Shizuku, root, mandatory ADB, companion app or second service application.
- Standard public Android APIs and best-effort read-only ordinary-app filesystem access.
- Never write sysfs, PMIC, I2C or proprietary charging controls.
- Never invent a measurement. Persist raw evidence, source, timestamp and unavailable/error status.
- Keep the UI responsive: filesystem traversal, telemetry collection, database summaries and exports run off the main thread.
- Preserve local history with non-destructive SQLite migrations.
- Save every coherent milestone and update this handoff/progress log in this GitHub branch so a later chat can resume.
- Monitoring is automatic after first app launch; no Start/Stop UI or service stop action. Resume is attempted on boot and app replacement.

## Current implementation

### Live telemetry
- Android battery percentage, status, plugged source, voltage, temperature, health enum, technology. Percentage is computed only from a valid Android EXTRA_LEVEL/EXTRA_SCALE pair; no default scale is invented.
- BatteryManager instantaneous and average battery-side current, charge counter and energy counter.
- Optional kernel fuel-gauge charge_full/design-capacity ratio and cycle_count when readable, kept separate from empirical capacity estimate.
- Charger-advertised maximum current/voltage, battery-present state, invalid-charger flag and optional cycle-count metadata when Android/OEM extras expose them.
- Battery-side power estimate only when voltage/current are plausible; never label this as USB input power.
- Best-effort charger-advertised maximum current/voltage, battery-present, invalid-charger and optional cycle-count extras.
- Read-only inventory of power_supply, Type-C, USB-PD, USB role, thermal, hwmon and selected non-serial USB descriptor attributes.
- Vendor/Oplus/VOOC fields are raw state indicators only; no proprietary handshake or protocol claims.
- Per-root and per-file visibility/read/list diagnostics; distinguishes definitely unavailable from permission denied and absent-or-SELinux-hidden.
- BatteryManager diagnostics distinguish unavailable sentinels, SecurityException and other exception classes for current, average current, charge counter and energy counter; missing battery broadcast/level/voltage/temperature extras are reported separately. Version 1.15 also validates current polarity and voltage range, records quality transitions, and excludes invalid readings from battery-side wattage.
- Usage Access status is displayed for foreground-app correlation.
- Live dashboard file traversal and BatteryManager reads run on a dedicated HandlerThread.
- Recorder event writes triggered by service/broadcast callbacks are queued on the recorder HandlerThread; database close is queued after pending work to avoid main-thread SQLite stalls and dropped events.
- BOOT_COMPLETED no longer opens SQLite on the broadcast main thread; automatic-resume failures are saved in preferences and surfaced in diagnostics.
- Duplicate MainActivity lifecycle override found during review was removed.

### Recording and analytics
- Automatically requested foreground service on Activity resume; boot and package-replacement receivers request recovery; 30-second BatteryManager sample target; 120-second kernel-tree inventory. Version 1.15 passed the authoritative Android CI build and all 22 JVM tests in run 45223861385. Physical RMX2161 validation remains outstanding.
- Selectable continuous mode (partial CPU wake lock for screen-off continuity) or low-impact mode (no permanent wake lock; screen-off gaps are expected and measured).
- Local SQLite sample/event history, monotonic elapsed time and boot count.
- Screen-on/off and power-connected/disconnected/battery-state events.
- Optional UsageStats foreground-package correlation when user grants Usage Access.
- 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries.
- Battery-side charge-counter mA rates and screen-state-specific historical charge/discharge projections.
- Percentage-point/hour and charge-counter-derived mA charge/discharge rates.
- Screen-on versus screen-off charge/discharge rates.
- Battery decline while charger remains connected.
- Recent charge/discharge session durations, SOC thresholds and historical time-to-full/time-to-empty estimates.
- Each recent session also separates display-on and display-off SOC rates, signed battery-side mA rates when charge-counter deltas are valid, and separate time-to-100% / time-to-0% forecasts. Display state is only a proxy for active use; foreground-app correlation is separately labelled approximate.
- App foreground time with coincident SOC movement and, when valid, charge-counter mAh movement. Attribution requires the same foreground package at both sample endpoints with the display on. This is correlation, not per-app electrical energy/current or causal attribution.
- CSV export includes chronological samples/events, boot metadata and raw hardware tree snapshots.
- SQLite schema v4 adds charger maximum current/voltage, battery-present, invalid-charger and optional cycle count without deleting v1/v2/v3 history.

### Capacity estimate
- Estimates full-charge capacity from charge-counter movement versus SOC movement across continuous sessions.
- Requires known boot count, same-boot continuity, valid counter and SOC values, at least 12 percentage points, direction agreement and plausibility checks.
- Median of sessions, spread and qualitative confidence.
- 4,500 mAh original design-capacity baseline for RMX2161.
- No forced 0% cycle. If the fuel gauge does not expose a usable charge counter, capacity remains unavailable.

## Device-wide monitoring expansion

Read `DEVICE_TELEMETRY_ARCHITECTURE.md` before implementing new collectors. It defines the target product, capability matrix, public API versus privileged boundary, device-specific unknowns, data-quality contract and phased acceptance gates.

Important newly identified sources:
- UsageStatsManager: foreground app transitions, usage aggregates and screen-interactive event aggregates. Detailed events are only retained for a few days, so regular import and deduplication are required.
- NetworkStatsManager: Android-maintained mobile/Wi-Fi byte buckets and per-UID traffic where access permits. Network bytes are not battery energy.
- StorageStatsManager: package/UID storage totals, queried on a worker because operations may take seconds.
- PowerManager: thermal status and thermal headroom; headroom is an estimate.
- ActivityManager.MemoryInfo: system RAM pressure.
- SensorManager: actual runtime sensor inventory; product-sheet sensor claims are not a substitute for enumeration.
- CPU/GPU/thermal kernel paths: best-effort read-only discovery only.

BatteryStats per-app power and detailed system battery history remain privileged. Ordinary-app code must not pretend UsageStats or network traffic provides per-app mAh. The exact RMX2161 firmware capability census remains pending.

## Important measurement limitations

- Battery-side current is not USB adapter current.
- Android max-charging-current/voltage extras are advertised maximums, not live input measurements.
- Live USB V/A/W requires a same-supply, semantically verified kernel voltage/current pair. A candidate is not confirmed until validated on the actual handset.
- PD PDO values indicate advertised capabilities, not necessarily the selected active contract.
- Android 12 may not expose battery cycle count or health capacity.
- Per-app battery energy is not publicly exposed to an ordinary third-party app on this device; UsageStats foreground correlation must remain labelled as such.
- Continuous CPU wake lock improves screen-off sampling but adds BatteryScope's own drain. Low-impact mode avoids the permanent wake lock but may miss screen-off samples. The notification and diagnostics disclose the selected mode. Measure comparisons with consistent recorder settings.
- Force-stop, shutdown, OEM process restrictions and Android power management can create sampling gaps. Report observed gaps; never fabricate continuity.

## Next engineering priorities

1. Verify the dedicated Android GitHub Actions workflow on the latest Battery-only main commit; repair every compile/test failure before APK delivery.
2. Inspect the exact workflow artifact, APK metadata, checksum and signing identity; do not assume update compatibility with older debug builds.
3. Continue auditing MainActivity and recorder for lifecycle races, main-thread I/O, long-running history queries and large-database performance.
4. Recheck schema-v4 migrations from v1/v2/v3 and verify all CSV header/sample/event rows align at 28 fields.
5. Add focused JVM tests for plugged-in drain, screen-state projections, usage correlation boundaries and database/export invariants where feasible.
6. Improve diagnostics where exact unavailable reasons can be distinguished from absent-or-SELinux-hidden paths.
7. Inspect the RMX2161's actual exposed attributes after installation; validate USB input V/I semantics, VOOC state, thermal zones and charge-counter availability from a diagnostic export.
8. Preserve signing-key continuity before releasing an APK intended to update an installed version. Do not advise uninstalling without protecting local history.
9. Only mark hardware validation complete after real handset evidence exists.

## Resume instructions for a future ChatGPT session

Read this file first, then `PROGRESS_LOG.md`, `README.md`, `AUDIT.md`, `PROJECT_STATUS.md`, `AGENTS.md`, the Gradle workflow and current branch source. Continue from the latest branch commit. Do not ask the user to restate the phone model, app name, package or no-Shizuku constraint. Do not claim a build or APK is verified until the actual workflow result/artifact has been inspected.


## Latest user-export findings (2026-11-23)

Review of `BatteryScope_History_20261123_214733.csv`: 22 samples / 51 events across 13m35.654s. SOC 83→80%; charge counter 3,735,000→3,600,000 µAh; temperature 33.8–35.4 °C. Every sample says NOT_CHARGING / NONE; therefore no charging-watt validation occurred.

Critical: voltage export is 3–4 in a column named `battery_voltage_mv`; source expects 2,500–5,000 mV and currently rejects these values for live power calculation. Current export is 14–674 in `battery_current_ua` (mean 201.9), but the charge-counter endpoint delta implies roughly 595.8 mA over the whole session; counter values move in coarse 45,000 µAh steps. Do not assume whether the OEM API returns volts/millivolts or mA/µA from this file alone. Do not apply a heuristic multiplier to historical data. Preserve raw readings and add explicit raw-unit/device validation before trusting battery-side watts.

Other gaps: average current, energy counter, cycle count, charger max values and USB V/I candidates were null; Usage Access was off in 20/22 samples; one sample interval was 154.741s; kernel roots were denied or absent/hidden and no fields were captured. This is a short recording, not proof of 24/7 continuity. Next: collect one unplugged discharge and one charging capture with Usage Access enabled, compare BatteryManager raw values to same-supply battery sysfs values if accessible, and independently verify voltage/current units before changing scaling. Never claim USB-input watts from battery-side values.


## 2026-11-23 forensic audit and v1.15 changes

Read [FORENSIC_AUDIT_2026-11-23.md](FORENSIC_AUDIT_2026-11-23.md) for the exact APK/artifact hashes, branch/build commit comparison, 55-sample RMX2161 export findings, source defects and verification limits. The new BatteryMeasurementQuality helper refuses to interpret current with a sign inconsistent with Android's documented convention, refuses voltage outside 2,500–5,000 mV, and returns no battery-side watts unless both inputs pass. Raw values remain unchanged. Quality transitions are written to the event history. The live battery summary is shorter and foreground UsageStats event timestamps now use the source event time.

Version is 1.15 / versionCode 16; applicationId remains com.batteryscope; database remains schema v4. Eight new JVM tests were added. Do not install a new CI debug APK over an existing installation until its signing-certificate digest is compared.

Outstanding: physical charging/discharging tests on RMX2161, 24-hour screen-off continuity test, stable production signing key for upgrade continuity, and implementation of the remaining device-wide collectors in DEVICE_TELEMETRY_ARCHITECTURE.md.


## Verified v1.15 artifact

The authoritative run is [45223861385](https://github.com/astramark06-sudo/test/actions/runs/45223861385), app source commit `480a567cd7c90f552976dc60472fa6ef11a5e126`. All 22 JVM tests passed. APK: 914,326 bytes, SHA-256 `0c8f37eb4777b9ece9a1241a85e4805bab06dd5fd4b0bdde339f748c384b32ac`; signer SHA-256 `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`. This signer differs from the previous v1.14 debug signer. Do not install over the existing app. See FORENSIC_AUDIT_2026-11-23.md for full artifact verification.
