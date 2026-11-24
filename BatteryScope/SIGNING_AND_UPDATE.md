# BatteryScope signing and update safety

## Current blocker

The historical installed v1.14 debug certificate differs from later CI-generated v1.15 certificates. Ephemeral CI debug APKs are **not** safe in-place updates unless the installed certificate is independently established and exactly matches. A new key cannot recreate the previous signing identity. This document does not authorize uninstalling an existing installation or deleting app data.

## Offline preflight

Keep the exact trusted APK that was installed on the handset, or independently establish its certificate SHA-256 fingerprint and version code. Do not substitute the SHA-256 of the entire APK file for the signing certificate fingerprint.

With Python 3.10+ and Android SDK Build Tools 35.0.0 installed on a trusted computer, from the repository root:

    python BatteryScope/tools/verify_apk_update.py candidate.apk --baseline trusted-installed.apk

Alternatively, if the installed certificate and version code are independently known:

    python BatteryScope/tools/verify_apk_update.py candidate.apk --expected-cert CERTIFICATE_SHA256 --installed-version-code 16

Both APKs are inspected using apksigner verify and aapt; the gate checks the package ID, certificate, version code and unchanged candidate file digest. It intentionally rejects unknown signing identity, multiple signers, certificate mismatches, downgrades and same-version release candidates. Same-version comparison is available only with explicit --allow-same-version for development. Certificate rotation/lineage requires separate expert verification.

For a proposed non-debug production update, add `--release-version-name EXPECTED_VERSION`. This additionally requires the exact candidate versionName, application metadata, no debuggable marker and no INTERNET permission. The normal ACCESS_NETWORK_STATE permission is permitted for local transport observation. The same-version override is rejected in release mode. All comparisons still require independently trusted installed identity.

IDENTITY_MATCH_ONLY is not installation, migration, or release approval. Record the exact candidate hash, CI run, migration backup and physical-device validation before release.

## Signing key custody

The repository does not contain, generate or solicit a private signing key. Keep any existing keystore and its credentials outside this public repository and outside chat. A future repeatable signed build needs an independently controlled signing identity and secure key custody; if the old key is unavailable, preserve the existing installation and history while evaluating recovery. Do not treat a new certificate as an update to the old app. A CSV backup is not lossless; portable backups need a compatible version already installed.

## Verification boundary

Unit tests exercise parsing and the decision policy using synthetic metadata. A real APK and a trusted baseline are still required for the actual gate. The helper never installs an APK, changes a device, or accesses a private key.
