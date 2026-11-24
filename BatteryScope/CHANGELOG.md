# BatteryScope changelog

## 2026-11-25 — Resource history summary

Added 24-hour resource-history summary from stored acquisition timestamps: observed RAM range, minimum available internal storage, peak Android thermal status, and per-counter network bytes over valid intervals with explicit time coverage. Excludes repeated cached snapshots, unknown boot identity, resets, wall-clock discontinuities and gaps over five minutes. Historical device samples prior to this collector remain readable and are excluded as unavailable; no synthetic backfill or schema change. CI pending.

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

## Unreleased — automatic monitoring and device-wide telemetry foundation
- Added a device-wide telemetry architecture and Android capability audit covering UsageStatsManager, NetworkStatsManager, StorageStatsManager, PowerManager thermal APIs, ActivityManager memory information, SensorManager inventory and best-effort kernel sources.
- Documented privileged BatteryStats limits, app-usage event retention, per-UID network semantics, and the requirement for an RMX2161 on-device capability census.
- Removed the obsolete Shizuku diagnostics source stub.
- Changed recorder lifecycle to request monitoring automatically when the app resumes and to request recovery after boot or app replacement.
- Removed the in-app Start/Stop controls and service stop action. Foreground-service, wake-lock, force-stop and Realme background-policy limitations remain.
- Build, test, APK-signing and physical-device validation are pending for these lifecycle changes.


## 1.14 — expanded telemetry, diagnostics and non-blocking live dashboard
- Moved live BatteryManager and kernel class-tree reads to a dedicated HandlerThread; dashboard refresh no longer traverses sysfs on the UI thread.
- Added a persisted continuous/low-impact recorder mode: continuous holds a partial CPU wake lock for screen-off continuity; low-impact releases it and exposes resulting sample gaps.
- Added best-effort charger-advertised maximum charging current/voltage, battery-present state, invalid-charger flag and optional cycle-count metadata to live telemetry and SQLite schema v4, with non-destructive v2/v3 migrations.
- Added explicit diagnostics for unavailable BatteryManager properties, Usage Access, per-kernel-root visibility and read/list failures; root paths hidden by SELinux are not falsely reported as definitely absent.
- Added a separate kernel fuel-gauge report in Battery Health for readable charge_full, charge_full_design and cycle_count values, with a reason when the driver does not export or the app cannot read them.
- Kept empirical capacity and kernel-reported capacity-health estimates separate.
- Expanded history with charge-counter-derived mA charge/discharge rates, screen-on/off rate comparisons and historical time-to-full/time-to-empty projections.
- Extended CSV export with the additional battery/charger fields.
- Preserved the 30-second recorder cadence and 120-second hardware-tree inventory cadence.
- Version code: 15.


## 1.13 — standalone battery capacity estimation
- Added a Battery Health panel estimating full-charge capacity from Android charge-counter movement relative to state-of-charge changes across continuous sessions.
- Uses up to 90 days of history, a 12-percentage-point minimum session change, same-boot continuity, direction checks, median aggregation, session spread and qualitative confidence.
- Uses 4,500 mAh as the RMX2161 design-capacity baseline; reports unavailable rather than fabricating capacity when the charge counter is missing or no valid sessions exist.
- Removed Shizuku dependencies, provider, UserService/AIDL integration and setup requirements. BatteryScope now uses standard Android APIs and ordinary-app access only.
- Removed privileged Oplus charging-control writes; no sysfs write path remains.
- Added estimator unit tests for nominal capacity and invalid data rejection.
- Version code: 14.


## 1.12 — CSV export integrity and session timing correction
- Fixed CSV record terminators: exported headers, samples and events now end with actual newline characters rather than literal backslash-n text.
- Centralized CSV row quoting/escaping and added JVM unit tests for commas, quotes, embedded line breaks and empty columns.
- Session screen-on duration and full-charge session boundaries now use the same monotonic-aware interval calculation as total session duration.
- Persisted Android BOOT_COUNT per sample, added a non-destructive SQLite v1-to-v2 migration, and use wall-clock duration across reboot even when post-reboot uptime is larger than the previous uptime.
- Moved CSV generation and copying to the selected document destination off the UI thread to avoid ANRs on large history exports.
- CI now runs testDebugUnitTest before assembling the APK.
- Version code: 13.

All versions are debug APK development builds unless a signed release is explicitly documented. Source changes and CI builds do not imply physical-handset testing.

## 1.11 — continuous recorder, session analytics and privileged history
- Added a user-started foreground recorder on a dedicated HandlerThread, independent of MainActivity visibility and Activity pause/destroy.
- Added a local SQLite sample/event store with 30-second Android battery sampling and 120-second bounded power_supply, Type-C, USB-PD, USB-role, thermal, hwmon and non-serial USB descriptor inventories.
- Added a recorder-owned optional read-only Shizuku UserService binding; saves power-path snapshots every 120 seconds and focused dumpsys batterystats --charged snapshots every 15 minutes. The complete audit remains on-demand.
- Added 24-hour, 7-day, 30-day, 90-day, 365-day and all-time streaming summaries; recent charge/discharge session ledgers; charge-counter changes; screen-on/off rates; plugged-in battery decline; app foreground correlation; threshold timing; and sampling-gap diagnostics.
- Uses monotonic elapsedRealtime for sample interval calculations within a boot, with wall-clock fallback across reboot.
- Prevents threshold projections when battery movement contradicts the CHARGING/DISCHARGING state.
- Added partial CPU wake lock during user-requested recording to reduce deep-sleep gaps; notification discloses that this increases the app's own power use.
- Added user-intent-based attempt to resume after BOOT_COMPLETED; powered-off time remains unrecorded and OS/OEM restrictions can prevent resume.
- Added Usage Access and battery-optimization settings shortcuts.
- Added durable app-private CSV export with chronological event/sample merge and wall-clock plus monotonic timestamps.
- USB input candidates now require USB-named or USB-typed supply identity; unrelated external supply V/I pairs are not labelled VBUS/IBUS.
- App-level drain remains correlation, not direct per-app electrical measurement. Hardware semantics and background continuity remain subject to RMX2161 runtime testing.
- Version code: 12.

## 1.10 — initial persistent telemetry recorder milestone
- Added a user-started foreground service, SQLite raw history, Usage Access correlation, event capture and CSV export.
- Version code: 11. Superseded by 1.11.

## 1.9 — expanded measurement diagnosis
- Expanded ordinary-app read-only discovery across power-supply, Type-C, USB-PD, dual-role USB, USB-role, thermal and hwmon class attributes.
- Added automatic diagnosis distinguishing live candidates, configured limits and denied/missing data.
- Version code: 10.

## 1.8 — expanded power-path discovery
- Expanded Shizuku and ordinary-app sysfs inventory.
- Added thermalservice and power-service dumpsys output.
- Version code: 9.

## 1.7 — launcher identity
- Added adaptive launcher icon resources and preserved application ID com.batteryscope.
- Version code: 8.

## 1.6 — telemetry labelling and CSV export
- Marked implausible Android battery voltages invalid in the UI and excluded them from battery-side power estimation.
- Clarified charge counter and Android health limitations.
- Added Storage Access Framework export for CSV.
- Version code: 7.

## 1.5 — Shizuku diagnostics baseline
- Added Android battery telemetry, sysfs audit, Shizuku diagnostic service, live power-path sampling, conditional Oplus cool_down control, CSV logging and diagnostic text display.
- Version code: 6.


## 1.14 — Reliability hardening (2026-11-23)

- Removed a duplicate Activity lifecycle override that would prevent Kotlin compilation.
- Moved recorder event database writes from main-thread service/broadcast callbacks to the recorder worker thread.
- Queued recorder database shutdown after pending worker operations.
- Removed SQLite access from the boot-completed broadcast receiver; boot-resume failures are retained in preferences and surfaced in diagnostics.
- Added a dedicated Android GitHub Actions build/test/APK-artifact workflow.
- No application ID, versionCode or SQLite schema change in this hardening pass.

- Recent charging/discharging session reports now separate display-on/display-off SOC rates, signed battery-side mA rates and time-to-threshold estimates.
- Per-app history now includes coincident charge-counter mAh movement when valid, explicitly labelled as foreground correlation rather than direct app power measurement.

- Added per-property BatteryManager access diagnostics and persisted access-status transitions; battery percentage now requires valid level/scale metadata and reports the exact missing/invalid input.


## 2026-11-23 — RMX2161 CSV field-validation findings

- Reviewed first user-exported history file (22 samples, 51 events, 13m35.654s).
- Found voltage values 3–4 in the `battery_voltage_mv` field and current values 14–674 in `battery_current_ua`; existing UI scaling cannot produce a valid voltage/power result from this export.
- Charge-counter endpoint movement implies ~595.8 mA average, but 45,000 µAh quantization and OEM/API unit uncertainty prevent a safe inferred multiplier.
- No charging session was present; USB input watts remain unvalidated. Average current, energy counter, cycle count, charger limits and USB V/I candidates were unavailable.
- No source conversion changed in this update. Raw values are preserved; calibration/semantic validation is required before correcting units.


## 1.15 — measurement quality and event chronology (2026-11-23)

- Added explicit validation for BatteryManager current polarity and battery-voltage millivolt range before calculating or displaying battery-side watts. Raw API values are retained; no sign inversion or unit multiplier is guessed.
- Added recorder events when current/voltage measurement quality changes.
- Simplified the main battery panel so battery level, state/source, voltage, current, battery-side power and charger-input availability are immediately visible; detailed telemetry remains in diagnostics.
- Preserved original UsageStats foreground event timestamps rather than stamping events at poll time.
- Added eight JVM tests covering current direction, ambiguous states, voltage range and guarded battery-side power.
- Bumped versionName to 1.15 and versionCode to 16. Package ID and SQLite schema v4 are unchanged.
- Physical RMX2161 validation remains required; charger-input watts and device-wide collectors are not claimed as implemented.
