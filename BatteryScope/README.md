# BatteryScope 1.15 — Realme Narzo 20 Pro (RMX2161)

## 2026-11-25 — Preserve informative earlier charging segments; artifact verification

- Real-device export (206 battery samples, 167 eligible charging samples) revealed 32 separate eligible segments. The eight newest cover only 14 observations and 4.89 observed minutes. Chart selection now preserves those eight plus up to eight longest earlier non-singleton segments, favoring newer on equal durations. Independent replay of the selection policy covers 150/167 observations, 96.93 observed minutes, 61 plotted checkpoints. No gap interpolation, merging or inferred electrical quantities; at most 16 segments × 16 points retained, with explicit coverage and thinning counts. Earlier omitted segments remain in raw history.
- Added two JVM tests for retention, stable tie behavior, coverage and bounded memory. New cumulative CI required. No database, export, application identity, signing, permission or history changes.
- Verified duration source 2f5bd75a: Android CI 45535878462 and signing 45535878438 both passed. Downloaded instrumentation artifact 14986512962 ZIP SHA-256 220d64c9b275244e0fad78699a9e48efb0af5183efc182d79b00e2ce30980486 matched metadata, CRC valid, 21 instrumentation tests passed (zero failures/errors/skips). Prior APK artifact 14986858203 has 91 JVM tests passed and matching ZIP/APK hashes. Independent restricted RSA APK-v2 check of exact APK bytes (AOSP v2 format, RSA signature, protected content digest, certificate/public key consistency) passed for chart and duration artifacts; one-byte protected-data tamper rejected in each. This is supplemental to CI apksigner, not a general-purpose APK verifier or Android install test. Duration signer e7fe031d2122da887f36c8d8dfcd69624499c9efe9e9a4e35c86a90dafc9625c differs from chart signer c05dbe7d07aa0c46952f60fd26040ab664d14fcb1221b994d9ede1d38ad88d01. Neither is trusted for updating the installed app.
- Applied branch 2eccf1bf includes advertised-limit report and UI regression; signing CI 45536347221 passed, Android 45536347212 running at preparation. Exact next action: verify current head and this CI, then fast-forward to this retention candidate only if cumulative tests succeed; inspect new CI and artifacts. Remaining blockers: handset and trusted signer continuity; actual charger input and OEM units unverified. No private CSV committed.

## 2026-11-25 — Chart UI regression and duration artifact checkpoint

- Added real Android main-thread chart-view regression covering empty data, actual SOC and temperature Canvas pixels, earlier-segment selection, missing temperature, retention across refresh, single-point plots and clearing stale visible values. It does not start the recorder, touch production history or claim handset visual validation. Requires new instrumentation CI.
- Current applied head 2f5bd75aba37f17e9a1bb11d49a38e2158501c87: signing run 45535878438 passed; Android 45535878462 still running at preparation. Downloaded duration artifact 14986858203: archive SHA-256 0c8c4eb457ca76a4841bc4e3731cef2f507bf3a41821a14ad7c0e73dc9a6caeb matched metadata, ZIP CRC valid, APK 1,025,030 bytes SHA-256 c5b35639d3a7a4e9a96b0313f2f36f3011ceea659c791915716a1d0212d9ec10 matched included report, 91 JVM tests (22 XML suites), zero failures/errors/skips. CI reports debug signer e7fe031d2122da887f36c8d8dfcd69624499c9efe9e9a4e35c86a90dafc9625c and v2 verification; local apksigner unavailable. Not a safe update.
- Prepared candidate includes advertised-limit commit 7e0a3bc1 and this UI test. Exact next action: check current branch and Android run 45535878462; after successful completion fast-forward to this candidate, verify cumulative Android/signing CI and exact artifacts. If a test fails, fix its actual failure before promotion. Remaining gates: handset chart/measurement validation, independently trusted installed signer, actual charger input availability; no guessed calibration. No history, identity, schema, permissions or signing changes.

## 2026-11-25 — Optional advertised limits in charging history

- Report raw positive int-domain optional max-charging-current/voltage fields from existing stored samples, with independent missing/rejected counts and paired coverage. No multiplication into watts; no active USB contract, measured input, calibrated limit or protocol inference. No new telemetry source, hidden API access, schema or export changes.
- Added JVM regression for unavailable/invalid/positive values and Android read-only database/dashboard snapshot regression (raw history before and after byte-identical).
- Verified previous chart checkpoint fa15f911: Android CI 45535355961 and signing 45535355859 passed; downloaded artifact 14986912452 ZIP SHA-256 6a37458a649403362b8fc6733ed0ead9a5c8d0f2c38560a7e65e154cbb23a9d8, CRC valid, APK SHA-256 6a0a200bda82026b2ce590adeb70edb09a408a2fc9791aa880da2998d8d0beb3, 1,022,586 bytes; all 89 JVM tests passed in included XML. CI apksigner reports debug signer c05dbe7d07aa0c46952f60fd26040ab664d14fcb1221b994d9ede1d38ad88d01; not independently re-run apksigner, not trusted for installed update.
- Duration/quality checkpoint 2f5bd75a advanced only after chart CI passed. Its Android CI 45535878462 must finish before advancing this candidate; signing CI 45535878438 passed. Next: check exact head and cumulative CI; if successful, fast-forward this candidate, run new CI, inspect test/artifact results, then verify native plot rendering and physical-device measurements. Trusted signer/device validation and actual input measurements remain blocked. No production history, permissions, signing, application ID, schema/version or private data changes.

## 2026-11-25 — Observed connected-time partition and electrical quality reasons

- The charging-focused 30-day report now partitions eligible same-source, known-same-boot 1–90s connected intervals into matching CHARGING endpoints, matching FULL endpoints, other known non-charging endpoints, and state-transition/unknown endpoints. Excludes source changes, gaps, invalid timing, unknown boots and reboot intervals. This is observed endpoint coverage, not exact active charging or complete plug-in duration. Counts and durations reconcile; no assumption of state throughout an interval.
- Voltage/current assessment reason counts reconcile to the number of eligible charging observations. Temperature coverage explicitly distinguishes usable and absent/invalid samples. No guessed scaling, polarity correction, charger input inference, new collection, permissions, schema/version/signing changes or history mutation. Two JVM regression tests added. New Android CI required; chart checkpoint fa15f911 must finish its cumulative CI before branch advancement. Physical device and safe update validation remain open.

## 2026-11-25 — Native recent charging observation plots

- Added native SOC and battery-temperature scatter plots for up to eight recent eligible continuous charging segments, newest first, with a segment selector and accessible numeric checkpoint table. Observed points only; no interpolation, connecting lines, assumed watts, or joining across recording gaps. A single sample is centered on the elapsed-time axis. Missing temperature is explicitly unavailable. Each segment retains at most 16 points. Text report and plots use the same bounded database snapshot and worker query; no extra scan or new stored fields.
- Added JVM test for segment retention/order and recorded start timestamp. Android compilation, instrumentation, visual layout and physical device verification required for this commit. No schema, identity, permissions, signing, history or CSV/backup changes. Prior source head 0435aa54 has successful build/unit/lint/metadata and signing-policy jobs; its emulator run must finish before advancing branch.

## 2026-11-25 — Charging curve checkpoints and SOC-band temperature

- Charging-focused 30-day report now includes per-SOC-band observed battery temperature ranges and the most recent eligible same-source, known-same-boot charging segment as elapsed-time/SOC/temperature checkpoints. Adaptive sequence thinning bounds retained points to 16, preserves observed endpoints, and never interpolates. A recording gap, invalid SOC, unknown boot, reboot or source/state change breaks the segment. A recorded decline remains visible. No electrical quantities are inferred. The checkpoint table is groundwork for a graphical chart, not a native graph or a complete plug-in session.
- Added JVM regressions for retention bounds, actual endpoint identity, segment splits, and temperature coverage. Previous head `9339969b0d36f34a9b90d6d43bd29a1317115e1f` passed Android CI 45487907197 and signing CI 45487907298; new cumulative CI required. Native chart, physical-device validation, actual charger input and trusted update signing remain open. No schema/version/permission/signing changes; no private export committed.

## 2026-11-25 — Charging-specific measurement evidence and SOC-band profile

- Last-30-day charging-only observations show raw voltage/current range, expected-unit/direction quality counts, battery temperature, usable API-convention battery power coverage, recorded unverified USB pair count and nominal SOC-counter proportionality. Never infer actual current or charger watts from SOC. No proprietary fast-charge status inference.
- Ten SOC bands summarize observed same-plug, known-same-boot charging intervals; exclude gaps, reversals and cross-band jumps rather than distribute unknown elapsed time. Retain zero-change intervals, report observed duration and percentage-point rate. No calibrated electrical or guaranteed forecast claim. Focused UI uses this report and skips general resource-history scans. Earlier exports remain intact. Added two pure JVM tests and expanded Android report regression. New cumulative CI required.

## 2026-11-25 — Charging-first scope (supersedes device-wide expansion)

User requested charging analysis rather than unrelated device telemetry. New recordings and dashboard exclude app tracking, CPU/GPU inventory, general resource snapshots, network statistics and sensors. Remove PACKAGE_USAGE_STATS and ACCESS_NETWORK_STATE declarations; no Internet or privileged access. Only battery properties, charging/power-supply/USB-C role/thermal read-only observations, screen state for charging comparisons, and recorder health remain. Old sample/event columns and existing backup/CSV formats retained; no deletion, including historical resource and app records. Historical reporting helpers remain for compatibility tests; normal UI uses charging-focused report and suppresses counter-derived mA figures that could be mistaken for actual current. Explicitly distinguish advertised maxima, raw flags, actual charger input (unverified) and battery-side estimates. Realme evidence: user supplied old.csv (377 samples) and New.csv (13 samples), zero exported USB pairs or advertised limits in both; all 13 new counters equal SOC times 45000 µAh, raw voltage 3–4 mV, charging current -568 to -330 µA-labelled. No calibration or unsupported rescaling. Thermal zones are not assumed to be battery temperatures. Previous full cumulative CI 45484754741 passed. New Android CI and real-device validation required. No version/schema/signing change. No charging-control writes.
 
## 2026-11-25 — Explicit exact-prefix overlap recovery (R16 partial)

- Added a separate user-confirmed portable-backup recovery option. Two full checksum/validation passes classify an earlier prefix per table, then compare every overlapping typed record to the existing ordered prefix, including duplicates. Only the earlier prefix is inserted; conflicting, later or interleaved records cause complete rollback. Existing earlier-only restore and CSV importer remain unchanged.
- Replaying an exact backup prefix is a no-op; no timestamp-only deduplication or arbitrary interleaving. The staged file remains private during processing, the boundary is rechecked under a write transaction, existing row bounds remain fixed. Backup provenance is unauthenticated, and cross-device import is not supported. Added Android exact overlap, duplicate, replay and conflict rollback regression. Long write transactions remain a performance limitation.
- No schema/version/signing/permission change or production data mutation. New cumulative CI required.

## 2026-11-25 — Bounded public resource inventory (R19 partial)

- Extend low-cadence resource snapshots with display state and current mode configuration, storage volume state/type (no labels/UUIDs/paths), sensor descriptors (max 64, explicit truncation), ABI list, time-zone offset and acquisition completion timestamps. Descriptors are not live sensor measurements. Display configurations are not panel power.
- Add only normal ACCESS_NETWORK_STATE permission for OS-reported active network transport/validation/metering snapshot. No INTERNET permission, active probes, SSID/BSSID, IP addresses, payload capture, persistent identifiers or sensor listeners. Connectivity snapshot can go stale immediately; event callbacks and Android system-history import are still pending.
- Add disposable Android integration test for section statuses, acquisition timing, descriptor bounds and permission boundary. Full device census, per-app aggregate imports, capability ledger, storage overhead and real-device verification remain open. Additional descriptor storage requires measurement; no schema or backup format change, history deletion, signing or version change.

## 2026-11-25 — Raw broadcast metadata and six resource history windows (R29/R33 partial)

- Preserve allowlisted integer/boolean/string battery broadcast extras, absent/wrong-type distinction and battery property status per sample in existing power_supply_json under @battery_raw_v1 and related metadata. Retain zero/negative voltage as raw evidence and signed in-range temperature. No arbitrary broadcast serialization. Read timestamp denotes app acquisition, not sensor timestamp. Missing prior raw fields cannot be reconstructed; property sentinel values still represented by status. Existing schema/CSV/portable format unchanged.
- Inventory-only consumers filter for inventory/resource keys so metadata-only battery rows cannot hide the last actual inventory. Resource analytics decode eligible snapshots in one ID-ordered scan across 24h/7d/30d/90d/365d/all-time acquisition windows, independent per-window totals and valid field counts. Last sample timestamp now uses readable local time and offset. Added JVM coverage and Android typed metadata/portable round-trip/window tests.
- Counter-duration regression fix ac20070 included in cumulative source. New CI required. Metadata increases storage and full-history JSON scanning; Android long-history latency/storage benchmark and persistent aggregation remain open. No signing, package, version, permissions, schema or history deletion changes.

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

**Prepare CSV history** creates a private export, then **Save / retry prepared export** opens Android's document picker. The same 28-column schema includes wall and monotonic timestamps, boot count, samples and events. Current exports group samples first and events second, each in original database record order; wall-clock rollback and duplicate timestamps do not reorder samples. Sort a separate analysis copy by wall time if desired; keep the original unedited for restore. Portable backups remain the lossless typed format. Generation, copying and verification run on background workers.

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
