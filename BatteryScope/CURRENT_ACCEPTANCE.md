# Current verified acceptance — BatteryScope

Date: 2026-11-25. This is the authoritative current checkpoint for draft PR #3, branch `fix/apk-update-preflight`. All earlier notes and the original 40-item audit retain their historical meaning. Current authorized scope is charging-first, not broad app/resource surveillance. This document records evidence; it does not authorize installation, release, merge, history deletion, hardware modification or new scope.

## Exact evidence

| Item | Verified result |
|---|---|
| Source | `b1811ba9482f741b884a4a706324a7d8ecdede95` |
| PR CI checkout | `f262bdc709db7409bc94abec53ba74ea737a7925`; identical Git tree `56901d4404338d39ec8f86f8b178dbaacb9afe3b` to source |
| Android workflow | [45665066410](https://github.com/astramark06-sudo/Battery-only/actions/runs/45665066410), both jobs successful |
| Signing/advisory workflow | [45665066602](https://github.com/astramark06-sudo/Battery-only/actions/runs/45665066602), 17 Python tests, OK in job log |
| Build artifact | 15046348898; 1,268,878 bytes; SHA-256 `1df0e6d595955a34f957c1d3326959472df5994d295e2807c3fdab1173abcf95` |
| Instrumentation artifact | 15047836045; 113,864 bytes; SHA-256 `b74addf5ac9c69a4b9517ecb2a16dbea9fd6a464361362a4bff625cffbf733ad` |
| Test XML | 104 JVM tests across 23 suites, 37 emulator tests, no failure/error/skip; 158 total including policy/advisory tests |
| APK | 1,187,845 bytes; SHA-256 `1b4ede7edad2e12d5214392ee1424323b077dd7c5275ba1ab893d5ade848ff9f`; checksum matches included report |
| APK signer | Single RSA 2048-bit v2 debug signer; cert SHA-256 `d11b8a2e438e7495ed644571f2b7b32f1c9eabe8a069cf800f49edc553d5672e` |
| App identity | com.batteryscope, versionName 1.15, versionCode 16, min SDK 26, target/compile SDK 35, database schema v5 unchanged |
| Lint | 0 errors, 67 warnings |
| OSV | 123 resolved Maven coordinates; 0 known matches, runtime gate no_known_matches; scan 2026-11-25T17:31:01.001472+00:00 |

Archives downloaded and independently SHA-256/ZIP CRC checked; XML parsed directly. APK independently verified with a separate restricted RSA-v2 parser: verified signed bytes against certificate public key, recomputed protected content digest over four chunks, and confirmed a protected-byte mutation fails the digest check. This supplements, does not replace, CI apksigner. Current debug signer is not a trusted installed signer. Exact source/CI merge tree verified with Git commit API. Tree listing contains no APK, keystore, PEM, CSV or portable backup by filename; this is not a comprehensive secret scan.

Exact packaged permission set and source manifest: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED (android.permission prefix). No INTERNET, ACCESS_NETWORK_STATE or PACKAGE_USAGE_STATS. Service and boot receiver are non-exported, launcher exported. Cloud/device-transfer exclusion resources and allowBackup=false; actual OEM backup behavior not validated. Current recorder source writes empty foreground_package and usage_access=0; old data remains preserved, not collected anew. Test suite includes charging-scope inventory/permission and backup flag assertions.

## Focused performance and security evidence

The emulator test `largeOlderHistoryDoesNotChangeRecentOverview` passed for 300 recent rows alone and 50,300 total rows (same 300 recent). Actual logcat observations in latest instrumentation artifact: 15.424035 ms and 10.423019 ms respectively for the overview call. Single synthetic observations, not a p95 or a physical phone benchmark, and do not imply older data makes queries faster. Entire test 0.578 s. Concurrent restore and append test passed (reported 0.102 s); preserves order and previously committed data. No claim of general contention-free restore, full-history scalability, disk exhaustion resilience or arbitrary provider crash recovery.

Batch 8 to 9 to 10 OSV matched distinct IDs: 60 → 10 → 0. The actual version selection and latest zero matches are verified, not an extrapolation. See [DEPENDENCY_SECURITY.md](DEPENDENCY_SECURITY.md) for scope, remediation and limits. No dependency lock/checksum-verification metadata, SDK/native/embedded library completeness, build reproducibility or comprehensive security guarantee claimed.

## Lint triage

67 warnings: 52 SetTextI18n (hardcoded strings), 4 ObsoleteSdkInt, 3 UsableSpace, 2 GradleDependency, 2 NewerVersionAvailable, 2 MonochromeLauncherIcon (base v26 variants; API33 variants provide monochrome), 1 OldTargetApi, 1 WakelockTimeout. None is a failing lint error. Retain min/target/compile SDK contract, current verified dependency versions and user-selected continuous mode rather than upgrade versions or change recording cadence merely to eliminate warnings. Low-impact mode/pause exist; continuous mode's indefinite partial wake lock remains an explicit real-device overhead risk. Free-space estimates deliberately do not allocate/reclaim cache or delete data. Localization, resource cleanup, broader platform and accessibility testing remain maintenance gaps.

## Original audit disposition (40/40 reviewed against cumulative checkpoints)

Original audit: [FULL_APP_AUDIT_2026-11-25.md](FULL_APP_AUDIT_2026-11-25.md). "Corrected" means the specific software defect addressed and cumulative tests passed, not validated hardware calibration or full coverage. "Mitigated" means a concrete implementation/test exists with a remaining limitation. "External" requires unavailable trusted signer or physical evidence. "Excluded" follows the changed charging-first scope and does not claim the original broader feature was implemented. "Policy" means a deliberate safety/compatibility restriction remains. "Debt" means nonblocking maintenance/coverage work remains.

| ID | Disposition | Evidence / remaining boundary |
|---|---|---|
| R01 | External | Fail-closed signer/version preflight tested; no trusted installed signer/key, non-debug release or data-preserving update. |
| R02 | External | Invalid raw voltage/current preserved and rejected; no calibrated replacement or actual charger input evidence. |
| R03 | Corrected | Broad source plausibility/sentinel and sign checks; not physical unit calibration. |
| R04 | Corrected | Per-session nominal proportionality, conservative confidence; health estimate not validated FCC. |
| R05 | Corrected | Segment reversals, known boot, 1–90 s and FULL endpoint policy; same-direction jump limitation remains. |
| R06 | Mitigated | Counter plausibility and quantization; no short-interval current certainty or calibrated gauge. |
| R07 | Corrected | Bounded wall-time/ID snapshot and future-row exclusion test. |
| R08 | Corrected | Threshold elapsed and remaining forecast regression. |
| R09 | Corrected | Empty/stale/gap/boot reporting; no guarantee of complete coverage. |
| R10 | Mitigated | Fixed monotonic cadence and isolated acquisition worker; real suspend timing and simultaneous sensor data unavailable. |
| R11 | Mitigated | Commit-only health, separate preferences and queue rejection; physical fault injection/OS lifecycle remain. |
| R12 | Mitigated | WAL, prevalidation, concurrent restore/append regression; write contention and physical low-disk behavior remain. |
| R13 | Mitigated | 30-day bounded passive overview, on-demand full reports, 50,300-row equivalence; unbounded retention by request, full-scale heap/disk/p95 unmeasured. |
| R14 | Corrected | Normalized domains and row bounds, strict parsing and transactional rollback; malformed imported data rejected, raw history retained. |
| R15 | Policy | Explicit unauthenticated-origin warning; checksum is corruption detection, not device identity. No new persistent hardware identifiers or incompatible format. |
| R16 | Policy | Earlier-only and exact-prefix overlap recovery tested; arbitrary interleaving/newer merge deliberately unsupported. |
| R17 | Mitigated | Private storage, no new app tracking, explicit export consent, backup exclusions; debug artifact, unencrypted exports and release gate remain. |
| R18 | Excluded | Historical app-usage import helpers retained for compatibility; no new collection under current scope. |
| R19 | Excluded | No expansion to unrelated app/network/resource inventory. |
| R20 | Mitigated | 37 Android 12L tests including actual Activity recreation, chart, permissions, transfers and DB; service lifecycle, API matrix, accessibility, coverage and real-device checks remain incomplete. |
| R21 | Corrected | Process-local liveness, persisted last attempt/commit distinguished; actual OS kill/reboot not validated. |
| R22 | Corrected | Separate telemetry and operation state; UI refresh regression; provider-specific behavior still unverified. |
| R23 | Mitigated | Application-scoped history coordinator, retained picker state and Activity recreation/scroll; rotation during real provider operations/process death unverified. |
| R24 | Mitigated | Synced staging, checksum readback, retry and explicit discard integration; external SAF/cloud and abrupt process death not tested. |
| R25 | External | Own sampler CPU/wall-time counters and disclosed wake lock; no actual energy, whole-app CPU or long soak measurement. |
| R26 | Mitigated | Streamed per-root enumeration/budgets and cooperative deadline; no hard blocking syscall timeout. |
| R27 | Mitigated | Candidate source, age/plug/ambiguity restrictions and gauge freshness; no simultaneous or electrically verified USB measurements. |
| R28 | Corrected | Root-aware raw vendor presentation and conditional verified-unit labels, unrelated roots excluded from new collection. |
| R29 | Corrected | Allowlisted raw typed extras, signed temperature, acquisition/status metadata, portable backup tests; missing historical extras cannot be reconstructed. |
| R30 | Corrected | Known boot and conservative endpoint interpretation, no active app attribution. |
| R31 | Corrected | Missing endpoint, reached threshold and elapsed-session regression; timezone/readable history labels. |
| R32 | Corrected | CSV per-table ID sequence and rollback roundtrip; text flattening/formula protection are documented lossy behavior, portable backup authoritative. |
| R33 | Excluded | New general resource history outside current charging-first scope. |
| R34 | Mitigated | Insets/spacing source change; actual API35/gesture/cutout/font-scale visual test not performed. |
| R35 | Mitigated | Charging dashboard, curves, SOC bands, scroll/state, pause, export tools and explicit source disclosure; localization and TalkBack/font/physical rendering unverified. |
| R36 | Mitigated | SHA-pinned top-level actions, exact selected dependency/toolchain reports, lint, OSV and protected APK checks; no lock or cryptographic dependency metadata or reproducible build guarantee. |
| R37 | Mitigated | Exact debug package/version/permission checks plus offline release metadata/signing policy; no production candidate or trusted installed identity. |
| R38 | Mitigated | Bounded row/staging/free space checks and validation before transactional insert; actual disk full/low-space device/cancellation coverage incomplete. |
| R39 | Corrected | Authoritative current acceptance and checklist; older evidence labeled historical. |
| R40 | Debt | Shared parsing and helper extraction, separate service/DB and history worker; large remaining Activity/DB classes, further refactoring not a prerequisite for agreed functionality. |

## Acceptance and stop rule

Current existing checklist: 6/7 items verified, 85.7% unweighted gate tally; the last item is deliberately grouped and requires all of the following. No claim of 85.7% time/effort or successful release. The original 40 audit findings are dispositions, not 40 closed defects.

- Trusted signing: independently establish the installed package's public certificate fingerprint and version from a trusted baseline. Current debug APK must not be installed over existing data. No private key/password in chat or repository; original signing identity/owner-controlled process must be available for an authorized data-preserving update. Release would require a non-debug candidate and increased versionCode plus complete preflight; do not change them prematurely.
- Physical measurement: on actual RMX2161 verify safe normal-use observed charging/temperature/state data, raw voltage/current and supported units against trustworthy evidence. Actual charger-input power, OEM protocol and capacity health remain unavailable or unverified where evidence does not support them. No forced battery discharge, hardware changes or calibration guesses.
- Physical lifecycle/overhead/history: verify normal-use recorder continuity, screen-off modes, reboot/OS restrictions, actual power/energy impact, UI rendering and export/restore preservation on the authorized compatible installation. No arbitrary history deletion or uninstall. Portable backup is unencrypted and has no authenticated device provenance; do not call checksum authentication.

No further identified blocking software defect within the agreed repository-side scope after current evidence review. Do not manufacture additional development batches, repeat CI unchanged, or imply a physical gate can be closed through source-only work. Maintenance and broader testing remain possible future work, not silently expanded acceptance requirements. Next action when authorized evidence is available: signing identity/preflight first; otherwise stop at the documented external gate. Keep PR draft and no release.
