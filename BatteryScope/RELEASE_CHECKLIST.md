# BatteryScope release checklist

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

> **Repository migration — 2026-11-24:** Current development is in [Battery-only/main](https://github.com/astramark06-sudo/Battery-only/tree/main). The former `batteryscope` branch in `ocr` is historical, not the active development branch. The [standalone Android build](https://github.com/astramark06-sudo/Battery-only/actions/runs/45348612049) passed for commit `45d65c6b624515bfd269e1eaa6380cb4611e050a` (artifact ID `14899402289`). Earlier source commits, checksums and signer records below remain historical evidence; no new device or signing-compatibility verification is claimed.

A source commit is not a release. Keep build verification, artifact integrity, installation and physical-device validation as separate results.

## Before release
- [ ] Confirm the active repository is Battery-only and the branch is main (or the intended PR branch) and inspect the complete diff from the previous verified checkpoint.
- [ ] Check all app files, manifest permissions/components, database schema/export, capacity-estimator logic, foreground-service lifecycle and boot receiver.
- [ ] Keep application ID / namespace com.batteryscope.
- [ ] Keep the separate OCR/Python project in the `ocr` repository unchanged.
- [ ] Confirm no Shizuku dependency/provider/AIDL, ADB setup, root or companion-app requirement exists.
- [ ] Confirm BatteryScope performs no sysfs or charging-control writes.

## Source and documentation
- [ ] Keep README.md, AUDIT.md, CHANGELOG.md, PROJECT_STATUS.md and this checklist consistent with the current code.
- [ ] Preserve source paths and raw readings; missing/denied is not zero.
- [ ] Check for secrets, device-private identifiers, APKs and build outputs in Git.
- [x] Added eight measurement-quality tests; all 22 JVM tests pass.

## Build and artifact
- [x] Increment versionCode and update versionName: 1.15 / code 16.
- [x] GitHub Actions Android build succeeds for app source commit 480a567cd7c90f552976dc60472fa6ef11a5e126 (run 45223861385).
- [x] APK package ID and version metadata are verified with aapt: com.batteryscope / 1.15 / code 16.
- [x] Workflow artifact 14840939491 downloaded from that exact run.
- [x] Artifact ZIP integrity, APK entry, nonzero size and SHA-256 are verified.
- [x] Recorded exact branch, commit, run, artifact, APK size and APK SHA-256 in FORENSIC_AUDIT_2026-11-23.md.
- [ ] Verify install-over-update compatibility when device testing is available.

## Physical RMX2161 validation — required before claiming device-tested
- [ ] Install/launch and verify update compatibility.
- [ ] Verify battery current/voltage/temperature/charge counter against system sources where available.
- [ ] Confirm current polarity agrees with Android BatteryManager semantics during both charging and unplugged discharge; invalid values must be flagged and excluded from wattage.
- [ ] Confirm raw voltage is a valid mV reading before battery-side wattage is displayed.
- [ ] Confirm quality-transition events are present in CSV and foreground UsageStats events preserve source timestamps.
- [x] Compared v1.15 and v1.14 debug signing certificates; they differ, so in-place update is not safe.
- [ ] Verify ordinary-app telemetry while unplugged and charging; unavailable paths remain explicitly unavailable.
- [ ] Confirm USB source identity and validate any VBUS/IBUS paths and units.
- [ ] Test recorder screen-off, multi-hour logging, notification, wake-lock power impact and gap diagnostics.
- [ ] Test reboot resume, force-stop behavior and Realme battery-management restrictions.
- [ ] Test Usage Access and foreground-app attribution.
- [ ] Verify the capacity estimator across several normal charge/discharge sessions; compare session spread and confidence.
- [ ] Test CSV content, chronological ordering, timestamps, SAF export and persistence of app-private copy.

## Final report
Report the exact commit/version, workflow run, artifact and checksums. Mark unperformed checks explicitly. A successful CI build is not proof of physical-device correctness, 24-hour continuity or real charger-side measurements.


## Verified v1.15 build record

- Workflow: https://github.com/astramark06-sudo/test/actions/runs/45223861385
- App source commit: `480a567cd7c90f552976dc60472fa6ef11a5e126`
- APK SHA-256: `0c8f37eb4777b9ece9a1241a85e4805bab06dd5fd4b0bdde339f748c384b32ac`
- Signer certificate SHA-256: `fe463192e3a055244ed627fbd8a8907f535263166e619c5bca686924b15094c2`
- Existing v1.14 debug certificate: `3c75edb5262b17164453949bb90113e133edc2daeceb10846cd233d2be8194b5` — mismatch; do not install over the existing app.
- Physical RMX2161 validation and stable production signing remain incomplete.
