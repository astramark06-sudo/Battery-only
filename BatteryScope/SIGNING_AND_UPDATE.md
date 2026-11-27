# BatteryScope signing and update safety

## Clean-install path — owner accepts loss of old app history

The owner has stated that discarding the existing installation is acceptable. This changes the path for a new installation, not the historical verification results. Do not remove the old app until a correctly signed, verified candidate is ready; optionally export any history that may still be valuable. Uninstalling deletes app-private history. Do not treat a new signing identity as an in-place update.

The [unsigned candidate workflow](../.github/workflows/batteryscope-unsigned-release.yml) builds the non-debug variant without signing secrets, checks the exact package/version and permission policy, archive integrity, alignment and lint, and uploads it separately from the debug artifact. Initial source commit `cb703a7b5c2465fc59a6785cd9c9a50d99313013`; CI result must be checked before using its output. This is not a signed or installable APK, and no public release is authorized.

One-time owner setup on a trusted computer with Java 17 and Android SDK Build Tools 36.0.0:

1. Create a private signing keystore outside the checkout (skip if a suitable owner-controlled key already exists). Use keytool or Android Studio's Generate Signed Bundle / APK wizard. Example: `keytool -genkeypair -v -keystore batteryscope-release.p12 -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias batteryscope`. Run this in a private directory. Choose and retain the password privately; back up the encrypted keystore securely. Never commit or send the keystore/password.
2. Download the successful workflow's unsigned candidate and check its SHA-256 against the included report. Use trusted SDK executables (full paths as needed). Align before signing if needed: `zipalign -v -p 4 app-release-unsigned.apk aligned.apk`. Then `apksigner sign --ks batteryscope-release.p12 --ks-key-alias batteryscope --out BatteryScope-release.apk aligned.apk`. Password prompts remain local. Reference: https://developer.android.com/build/building-cmdline.
3. Run `apksigner verify --verbose --print-certs BatteryScope-release.apk` and `aapt dump badging BatteryScope-release.apk`. Independently obtain the owner key certificate SHA-256 via `keytool -list -v -keystore batteryscope-release.p12 -alias batteryscope`; compare the entire fingerprint with apksigner. Verify package com.batteryscope, version 1.15/code 16, no debug marker, and the exact five permissions. Record the public certificate fingerprint and signed APK SHA-256. For the first installation there is no prior version to compare; do not fabricate an installed version for the update gate.
4. Only after those checks, the owner may remove the old app and install the new signed APK. Confirm normal launch and recording, physical measurement, screen-off and reboot behavior, overhead and history/export/restore as described in CURRENT_ACCEPTANCE.md. Preserve the new signed baseline APK and signing key. All subsequent updates must use the same trusted certificate and increased versionCode and pass the existing update preflight.

The clean-install path avoids needing the old key; it does not establish physical acceptance or future update compatibility until those checks are performed. The prior 6/7 gate tally remains historical evidence, not release approval.

## Latest verified development candidate — 2026-11-25

Source `76009d5b8169a63a955b800e28c586efe6af9be0`, Android run 45669685429, debug APK SHA-256 `6f2b1e4960038f478efa5d85959b1ea8c8c67e74415b78ee737ade5ec3661513`, certificate SHA-256 `dc7b8ddc9e31804df7a3580bc72a739e9744d8040aea504a259fbaba1efdb56b`. Independent RSA v2/protected-content and tamper checks passed. The shared release permission policy is verified in CI and against actual aapt output; the real debug marker prevents release acceptance. Despite identical application payload to batch 10, the certificate differs; installed identity is unknown. The existing offline helper locates Build Tools 35.0.0 by default; if using the current CI's Build Tools 36.0.0, use explicit `--apksigner` and `--aapt` paths to the trusted SDK executables. Do not confuse executable discovery with signing trust.

## Historical verified development candidate — batch 10

Batch 10 source `b1811ba9482f741b884a4a706324a7d8ecdede95` generated a debuggable APK with SHA-256 `1b4ede7edad2e12d5214392ee1424323b077dd7c5275ba1ab893d5ade848ff9f`, certificate SHA-256 `d11b8a2e438e7495ed644571f2b7b32f1c9eabe8a069cf800f49edc553d5672e`. Independently verified RSA v2 signature and protected content; deliberate mutation rejected. The certificate differs from the documented historical v1.14 and v1.15 debug certificates. The actual installed signer is still unknown; no safe update or release is established. See [CURRENT_ACCEPTANCE.md](CURRENT_ACCEPTANCE.md).

## Current blocker

The historical installed v1.14 debug certificate differs from later CI-generated v1.15 certificates. Ephemeral CI debug APKs are **not** safe in-place updates unless the installed certificate is independently established and exactly matches. A new key cannot recreate the previous signing identity. This document does not authorize uninstalling an existing installation or deleting app data.

## Offline preflight

Keep the exact trusted APK that was installed on the handset, or independently establish its certificate SHA-256 fingerprint and version code. Do not substitute the SHA-256 of the entire APK file for the signing certificate fingerprint.

With Python 3.10+ and Android SDK Build Tools 35.0.0 installed on a trusted computer, from the repository root:

    python BatteryScope/tools/verify_apk_update.py candidate.apk --baseline trusted-installed.apk

Alternatively, if the installed certificate and version code are independently known:

    python BatteryScope/tools/verify_apk_update.py candidate.apk --expected-cert CERTIFICATE_SHA256 --installed-version-code 16

Both APKs are inspected using apksigner verify and aapt; the gate checks the package ID, certificate, version code and unchanged candidate file digest. It intentionally rejects unknown signing identity, multiple signers, certificate mismatches, downgrades and same-version release candidates. Same-version comparison is available only with explicit --allow-same-version for development. Certificate rotation/lineage requires separate expert verification.

For a proposed non-debug production update, add `--release-version-name EXPECTED_VERSION`. This additionally requires the exact candidate versionName, application metadata, no debuggable marker and the exact five unconditional app permissions documented in CURRENT_ACCEPTANCE.md. The release helper and Android debug CI share one allowlist check. Missing, extra, duplicate, implied, SDK-conditional or malformed permission declarations fail closed. This checks aapt badging, not a complete manifest audit. In particular, INTERNET, ACCESS_NETWORK_STATE and PACKAGE_USAGE_STATS are not allowed. Do not interpret passing preflight as release approval. The same-version override is rejected in release mode. All comparisons still require independently trusted installed identity.

IDENTITY_MATCH_ONLY is not installation, migration, or release approval. Record the exact candidate hash, CI run, migration backup and physical-device validation before release.

## Signing key custody

The repository does not contain, generate or solicit a private signing key. Keep any existing keystore and its credentials outside this public repository and outside chat. A future repeatable signed build needs an independently controlled signing identity and secure key custody; if the old key is unavailable, preserve the existing installation and history while evaluating recovery. Do not treat a new certificate as an update to the old app. A CSV backup is not lossless; portable backups need a compatible version already installed.

## Verification boundary

Unit tests exercise parsing and the decision policy using synthetic metadata. A real APK and a trusted baseline are still required for the actual gate. The helper never installs an APK, changes a device, or accesses a private key.
