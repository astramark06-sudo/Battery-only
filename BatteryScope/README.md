# BatteryScope 1.15 — Realme Narzo 20 Pro (RMX2161)

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

BatteryScope is evolving into a local-first Android device telemetry and history system. The current implementation records battery/charging data and selected app-use correlations; the broader device-wide collectors are being designed and implemented in phases. It uses standard Android framework APIs and best-effort read-only access from its ordinary app process. It does not require Shizuku, root, ADB setup or a companion app.

The device-wide capability matrix, lifecycle design and implementation gates are documented in [DEVICE_TELEMETRY_ARCHITECTURE.md](DEVICE_TELEMETRY_ARCHITECTURE.md).

Application ID: `com.batteryscope`  
Version: 1.15 (versionCode 16)  
Target handset: Realme Narzo 20 Pro RMX2161, Android 12, non-rooted  
Build toolchain: Java 17, Gradle 8.9, Android SDK 35

## Battery telemetry

Where the phone's fuel gauge and ROM expose them, Android BatteryManager provides state of charge (only when Android supplies a valid level/scale pair), battery voltage, temperature, health state, plug type, technology, instantaneous and average battery-side current, charge counter, energy counter, battery-present state, invalid-charger flag, charger-advertised maximum charging current/voltage and (on newer Android/OEM builds) cycle-count metadata.

Important distinctions:
- BatteryManager current and voltage are battery-side measurements, not USB adapter output.
- The charge counter is an estimate of remaining charge, not full-charge capacity (FCC).
- Android health GOOD is a framework status, not a measured battery-health percentage. When readable, the app also reports kernel fuel-gauge `charge_full`, `charge_full_design` and `cycle_count` attributes; the charge-full/design ratio is shown separately from BatteryScope's empirical history estimate.
- Missing, denied or invalid readings remain unavailable; they are never substituted with zero. The diagnostics distinguish Android unavailable sentinels, security exceptions, other property-read errors and missing battery-broadcast extras where the API exposes that distinction.

**Measurement quality guard:** BatteryManager current is accepted for signed battery-side watts only when its direction agrees with Android's documented convention and the battery state. Battery voltage must be a plausible millivolt value. Raw values are retained and flagged when invalid; the app does not flip signs or guess OEM unit multipliers. Charger-input watts remain a separate measurement and are shown unavailable unless a verified USB-side voltage/current pair exists.

BatteryScope also attempts bounded, read-only inspection of ordinary-app-visible power-supply, Type-C, USB-PD, USB-role, thermal and hwmon class attributes. Android/Realme permissions may deny individual paths. Any same-supply USB voltage/current pair remains a candidate until its semantics are verified on the handset. The app does not infer charger input watts from battery-side current, decode the proprietary SuperDart wire handshake, negotiate charging protocols or write sysfs controls.

## Battery health and capacity estimate

The Battery Health panel estimates current full-charge capacity from recorded Android charge-counter changes compared with state-of-charge changes across continuous charging/discharging sessions.

- Original design capacity used for this handset: 4,500 mAh.
- The estimator examines up to 90 days of local recorder history.
- A session is considered only when Android exposes a boot count, the samples remain continuous within that boot, percentage and charge-counter readings are valid, the session changes by at least 12 percentage points, and the charge-counter direction agrees with the percentage change.
- The app takes the median of valid session estimates and reports session count, spread and a qualitative confidence level.
- Health percentage is estimated capacity divided by 4,500 mAh. It is not clamped to 100%; a result above 100% indicates estimation variance, not a larger guaranteed battery.
- If the handset does not expose a usable charge counter, the app reports that capacity is not measurable rather than inventing a value.

Keep the recorder enabled during ordinary use and charging to collect enough sessions. A full 0–100% discharge cycle is not required. The result is an estimate, not a laboratory capacity test; temperature, fuel-gauge calibration, percentage quantization and charging behavior can affect it.

## Continuous local recorder

The foreground recorder starts automatically when the app is opened and resumes after boot or app replacement when Android permits. No Start or Stop button is required. It samples:
- Android BatteryManager telemetry every 30 seconds.
- Read-only ordinary-app-visible power-supply, Type-C, USB-PD, USB-role, thermal, hwmon and non-serial USB descriptor inventory every 120 seconds.
- Power connection, battery state, screen on/off and battery-low transitions.
- Optional foreground-app tracking after the user grants Android Usage Access.

The recorder stores raw samples and events in a local SQLite database. History views include 24-hour, 7-day, 30-day, 90-day, 365-day and all-time summaries; charging/discharging percentage rates and charge-counter-derived mA rates; screen-on/off comparisons; plugged-in battery decline; app foreground-time correlation with coincident SOC and, when valid, charge-counter mAh movement (same foreground package at both sample endpoints while the display is on); session start/end and threshold times; recent session history with separate display-on/display-off SOC and battery-side current rates; and sampling-gap diagnostics. The app estimates time to 100% and 0% using both historical screen-on/screen-off rates and the current session's own screen-state rates. These forecasts are approximate and are labelled as historical estimates. Foreground-app battery decline and charge-counter movement are temporal correlation, not direct per-app current or energy measurement or proof of causation.

The recorder has two selectable sampling modes. **Continuous** (default) holds a partial CPU wake lock to improve screen-off 30-second sampling continuity; this increases BatteryScope's own power use and can bias discharge measurements. **Low-impact** releases the permanent wake lock; Android may suspend the process and create screen-off sampling gaps, which history reports. Toggle the mode in the app and keep the same mode when comparing runs. The ongoing notification identifies the selected mode. Monitoring itself starts automatically and has no in-app Stop control. Recording remains best-effort: force-stop, powered-off time, Android/Realme restrictions or service failure can interrupt it. Boot and app-replacement resume are attempted automatically, but uninterrupted execution is not guaranteed.

## Export and storage

**Export all history** creates a CSV in app-private storage and opens Android's document picker to copy it to a user-selected destination. CSV rows include wall-clock timestamps, monotonic elapsedRealtime timestamps, boot count, sample data and event rows in chronological order. The app retains the internal export copy. CSV generation and copying run on background threads.

Raw SQLite history has no automatic retention cap; storage use grows with recording duration. Export important history before uninstalling or clearing app data.

## Permissions and setup

1. Install and open BatteryScope; monitoring starts automatically.
2. Allow notification visibility if Android asks (Android 13+).
3. Grant Usage Access only if app usage/network/storage history is wanted.
4. Review Android/Realme battery-management settings if diagnostics report background restrictions.
5. Use the export control whenever you need a copy of local history.

Android may prevent automatic recovery after a force-stop. A foreground service and wake lock improve sampling continuity but cannot override every OEM or OS restriction.

No Shizuku app, root, ADB setup or companion service is required.

## Build and validation

GitHub Actions is configured to build a debug APK with Java 17, Gradle 8.9 and Android SDK 35, run JVM unit tests, check package metadata, verify the APK signature and report its certificate digest, and upload the APK plus build information. The workflow's result must be checked for the exact commit before treating an artifact as verified. A successful CI build proves compilation and the configured checks only; it does not prove installation, measurement accuracy, background continuity or capacity-estimate accuracy on the physical RMX2161.

See `AUDIT.md` for measurement limits, `CHANGELOG.md` for source history, `PROJECT_STATUS.md` for verification state and `RELEASE_CHECKLIST.md` for the release gate.
