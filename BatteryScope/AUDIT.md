# BatteryScope 1.15 — Measurement and implementation audit

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


## 2026-11-25 — Android integration verification

Added CI-only Android 12L instrumentation job and isolated SQLiteOpenHelper tests for actual v4 migration, duplicate timestamps, portable backup round-trip, exact REAL and signed INTEGER preservation, corruption/overlap rollback, earlier-history ordering and legacy CSV import. The app itself does not require ADB or emulator setup. Backup reads numeric cursor values by their declared type rather than using platform-dependent string formatting. App ID, version and database schema unchanged; physical RMX2161 migration remains unverified. CI pending.

## 2026-11-25 — Shared inventory and resource telemetry

Unified dashboard and recorder hardware inventory via shared bounded read-only allowlist, full source paths in stored JSON, cached snapshots with acquisition timestamps. Added public Android RAM, internal storage, TrafficStats device/mobile/own UID cumulative bytes, power-save/idle/interactive/thermal status and headroom, processor count and sensor-type inventory. No extra permission, sensor streams, network transmission or schema/CSV changes. CPU/GPU frequency fields are raw optional sysfs only, not utilization. USB candidate requires online=1 and same power_supply source. Dashboard resource snapshot refreshes no faster than 60 s; recorder inventory cadence remains 120 s. Device support and monitoring overhead remain unverified. CI pending.

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

Audit date: 2026-11-23  
Branch: `batteryscope`  
Target: Realme Narzo 20 Pro (RMX2161), Android 12, non-rooted

## Architecture and safety boundary

BatteryScope is a standalone Android application. It uses standard Android framework APIs and best-effort reads available to its ordinary application UID. There is no Shizuku integration or dependency, no ADB setup, no root requirement and no companion application. The former privileged UserService and charging-control path have been removed. BatteryScope performs no sysfs writes, PMIC/I2C writes, shell commands or undocumented protocol commands.

## Battery telemetry provenance

| Signal | Source | Interpretation |
|---|---|---|
| State of charge | `ACTION_BATTERY_CHANGED` | Percentage computed only from a valid EXTRA_LEVEL/EXTRA_SCALE pair; missing or out-of-range inputs remain unavailable |
| Battery voltage | `EXTRA_VOLTAGE` | Battery-side voltage, mV |
| Battery temperature | `EXTRA_TEMPERATURE` | Tenths of °C |
| Instantaneous current | `BATTERY_PROPERTY_CURRENT_NOW` | Battery-side current, µA |
| Average current | `BATTERY_PROPERTY_CURRENT_AVERAGE` | Battery-side average current, µA |
| Charge counter | `BATTERY_PROPERTY_CHARGE_COUNTER` | Estimated remaining charge, µAh |
| Energy counter | `BATTERY_PROPERTY_ENERGY_COUNTER` | Energy counter, nWh |
| Framework health | `EXTRA_HEALTH` | Android status enum, not capacity health |
| Charger maximum current/voltage | `max_charging_current` / `max_charging_voltage` broadcast extras | Advertised maximum values when reported; not live measured USB current/voltage |
| Battery presence | `present` broadcast extra | Only reported when the extra exists |
| Invalid charger flag | `invalid_charger` broadcast extra | Framework-reported charger warning flag when supplied |
| Cycle count | optional `cycle_count` extra | Best-effort metadata; not guaranteed on Android 12/OEM builds |

Battery-side current is not USB input current. Voltage × current is a battery-side power estimate, not adapter output power. Android's unavailable sentinels and permission failures are retained as unavailable; they are not converted to zero. Live diagnostics distinguish the Android unavailable sentinel, SecurityException and other exception classes for each BatteryManager property, while the recorder logs only property-access state transitions to avoid event spam. Where ordinary-app-readable battery/BMS sysfs nodes expose `charge_full` and `charge_full_design`, BatteryScope reports their ratio as a separate kernel fuel-gauge capacity-health estimate. It does not silently merge this with the charge-counter/SOC empirical estimate. A `cycle_count` attribute is also surfaced when readable.

## Capacity estimation method

The capacity estimator uses the local recorder's percentage and charge-counter history. It considers a maximum 90-day window and partitions observations into continuous charging/discharging sessions. A session is eligible only when:
- timestamps are continuous within a 90-second sampling gap;
- boot count is unchanged;
- state-of-charge and charge-counter values are present;
- percentage changes by at least 12 percentage points;
- charge-counter movement has the same direction as percentage movement; and
- the derived capacity falls within a broad plausibility interval of 1,500–8,000 mAh.

For each eligible session:

[
FCC_{session}(mAh)=\frac{|\Delta chargeCounter(\mu Ah)|}{|\Delta SOC|/100\times1000}
]

The app reports the median session estimate, not a single selected session. Confidence is HIGH for at least five sessions with no more than 15% full-range spread, MODERATE for at least three sessions with no more than 25% spread, and LOW otherwise. Health is estimated as median FCC divided by the RMX2161's 4,500 mAh design capacity.

The result remains an estimate. Fuel-gauge calibration, charge-counter quantization, temperature, battery aging behavior and SOC rounding can bias it. The app does not force a 0% discharge cycle. If no valid charge-counter sessions exist, it reports that capacity is not measurable rather than inventing an estimate.

## Ordinary-app kernel visibility

The app attempts bounded, read-only enumeration of power_supply, Type-C, USB-PD, USB-role, thermal and hwmon class attributes. Android/Realme SELinux and file permissions may deny individual attributes. Each raw path/value is retained where readable. A same-supply voltage/current pair is only a candidate when supply identity indicates USB; its exact semantics still require physical-device validation.

PDO records describe advertised capabilities, not proof of an active contract. Oplus VOOC/SuperDart flags are driver state, not electrical wire capture. The app does not infer charger input watts from battery-side current.

## Recorder and storage limitations

The foreground recorder samples Android battery telemetry every 30 seconds and bounded class-tree inventory every 120 seconds. Live dashboard sysfs traversal and BatteryManager reads execute on a dedicated worker thread rather than the UI thread. Recorder event writes arriving from Service lifecycle callbacks and broadcast receivers are queued onto the recorder HandlerThread; shutdown queues SQLite closure after pending work. The BOOT_COMPLETED receiver no longer opens SQLite on Android's broadcast main thread; startup failures are persisted for the in-app diagnostics panel. The dashboard reports Android property sentinels, Usage Access status, kernel class-root visibility, per-file read/list errors, and root paths that may be absent or SELinux-hidden. It uses a partial CPU wake lock while active, increasing the app's own power use. Recording is best-effort and may be interrupted by force-stop, powered-off time, Android/Realme restrictions or service failure. Boot and app-replacement resume are now requested automatically without a user-enabled gate; this source change passed v1.15 CI build and unit tests but still requires physical-device validation.

SQLite history is local and has no automatic retention cap. CSV export merges samples and events chronologically and includes wall-clock, monotonic and boot-count fields plus charger maximum-current/voltage extras, battery presence, invalid-charger flag and optional cycle count. History analytics report SOC percentage/hour, charge-counter-derived mA, screen-on/off charging and discharging rates, plugged-in decline, foreground-app correlation, session durations and approximate historical time-to-full/time-to-empty. Recent sessions separately estimate display-on and display-off SOC rates, signed battery-side mA rates and time to the target SOC. Per-app reports include coincident SOC and charge-counter mAh movement only when the same foreground package spans both sample endpoints while the display is on; this remains temporal correlation, not direct per-app energy measurement or causal attribution.

## Verification boundary

JVM tests cover estimator arithmetic and invalid-session rejection. CI compilation and tests do not establish that RMX2161 exposes a usable charge counter or that the estimate matches a laboratory capacity test. Physical handset validation remains required.


## Build pipeline and source-review fixes

A dedicated GitHub Actions workflow now targets the `batteryscope` branch and Android project paths. It installs Java 17, Android SDK 35 and Gradle 8.9; runs `:app:testDebugUnitTest`; assembles the debug APK; verifies package ID and versionCode; calculates SHA-256, verifies the APK signature and prints the signing-certificate digest; and uploads the APK and test report. Workflow success and artifact integrity must be checked against the exact source commit before release.

A duplicate `MainActivity.onDestroy()` override discovered in source review was removed. This was a Kotlin compile blocker. Source version is now 1.15 / versionCode 16. Package identity and SQLite schema v4 remain unchanged.


## Device-wide API capability audit — 2026-11-23

A new [DEVICE_TELEMETRY_ARCHITECTURE.md](DEVICE_TELEMETRY_ARCHITECTURE.md) records the wider system-information objective and a source-by-source capability matrix. Identified public/user-granted sources include UsageStatsManager, NetworkStatsManager, StorageStatsManager, PowerManager thermal APIs, ActivityManager memory information and SensorManager inventory. Their permissions, time retention, query latency and measurement semantics are documented there.

BatteryStats system history and per-UID battery energy require privileged BATTERY_STATS access; the app must not claim these are available to an ordinary install. Usage duration, UID network bytes and coincident SOC change are not equivalent to per-app electrical energy.

The Realme Narzo 20 Pro official specification confirms Helio G95, Mali-G76 MC4, 4500 mAh typical battery, 65 W SuperDart, listed wireless radios and physical sensor categories. It does not confirm which sysfs nodes, fuel-gauge counters, charger input telemetry or GPU/CPU statistics are readable by BatteryScope on the installed Android 12 firmware. An on-device capability census is required.

Lifecycle source update: recorder start/stop UI and the service stop action have been removed; app resume requests monitoring, and boot/package-replacement receivers request recovery automatically. Continuous execution remains best-effort because force-stop, shutdown, Android power management and Realme OEM restrictions cannot be overridden by an ordinary app. The v1.15 CI build passed; physical validation is pending.


## Field validation: RMX2161 export review — 2026-11-23

A user-provided CSV contained 22 samples and 51 events over 13m35.654s. SOC 83→80%; charge counter 3,735,000→3,600,000 µAh; temperature 33.8–35.4 °C. All samples were NOT_CHARGING with plug NONE. No charging session or USB-input power was observed.

The exported `battery_voltage_mv` values were 3 or 4, not values in the expected millivolt range. Source inspection confirms MainActivity only accepts 2,500–5,000 for battery voltage and therefore suppresses the battery-side watt calculation for these readings. The `battery_current_ua` values were 14–674 (mean 201.9; median 132), while the UI divides by 1,000 to display mA. The charge-counter endpoint delta corresponds to about 595.8 mA average over the full interval, but the counter is visibly quantized in 45,000 µAh steps. This points to unresolved OEM/API scaling and/or fuel-gauge quantization; it does not establish a safe conversion. Raw units must remain unchanged until independently validated.

Average current, energy counter, cycle count, charger max-current/max-voltage and USB V/I candidates were null in every sample. Usage Access was disabled for 20/22 samples. There were 21 intervals: mean 38.84s and one 154.741s gap. Kernel tree capture yielded no fields because roots were denied or absent/hidden. This dataset validates persistence/export only; it does not validate measurement semantics, charger input watts or continuous background reliability.


## 2026-11-23 — Forensic audit and measurement-quality correction

The complete source/APK review, CSV evidence, integrity metadata, confirmed defects, corrective changes and verification boundaries are recorded in [FORENSIC_AUDIT_2026-11-23.md](FORENSIC_AUDIT_2026-11-23.md). The extended RMX2161 CSV shows a current-polarity conflict with Android's BatteryManager contract and raw voltage values 3–4 in the mV field. Version 1.15 adds explicit polarity/range validation, suppresses battery-side watts when either input is invalid, preserves raw readings, logs quality transitions, simplifies the main battery panel and preserves source UsageStats event timestamps. Eight new JVM tests cover the quality guard.

The v1.15 APK artifact is checksum-matched to its workflow artifact, ZIP/DEX integrity-checked, and its v2 signature verified by CI. Run 45223861385 passed all 22 JVM tests. The v1.15 debug signer differs from v1.14, so in-place update is not compatible. Physical RMX2161 validation remains pending. This correction does not establish charger-input watts or complete the device-wide collector roadmap.
