# BatteryScope — standalone Android repository

BatteryScope 1.15 (versionCode 16) is a local-first battery and device telemetry application for Android. Application ID: `com.batteryscope`.

- [App documentation](BatteryScope/README.md)
- [Project status](BatteryScope/PROJECT_STATUS.md)
- [Release checklist](BatteryScope/RELEASE_CHECKLIST.md)
- [Android build workflow](.github/workflows/batteryscope-android.yml)

## Build
GitHub Actions builds the development debug APK, runs JVM and Android emulator tests, checks packaged permissions and signing metadata, and captures lint/dependency evidence on relevant changes. For the current verified PR toolchain, use Java 17, Gradle 9.4.1, Android SDK platform 35 and Build Tools 36.0.0. From `BatteryScope/`, run `gradle --no-daemon --no-build-cache --no-configuration-cache :app:testDebugUnitTest :app:assembleDebug`. See the workflow for the complete CI procedure.

## Current development acceptance

The verified development work is in draft [PR #3](https://github.com/astramark06-sudo/Battery-only/pull/3), not merged into `main`. See [current acceptance evidence](BatteryScope/CURRENT_ACCEPTANCE.md) for exact source, CI, artifacts and remaining gates. The current debug APK is not a trusted update; physical handset validation, trusted installed signing identity and history-preserving update remain unverified. Do not uninstall or distribute this candidate as an update.

## Migration provenance
The 37 original Android project files were imported unchanged from [ocr commit c3cc95c](https://github.com/astramark06-sudo/ocr/tree/c3cc95cf2dfa29e3c85af02c2aa65fc769635b7d/BatteryScope). The workflow trigger was then adapted to this repository's `main` branch. Historical documentation references the original repository and CI runs; those are historical evidence, not new builds. The original commit history remains in `ocr`. OCR, Python, AI tooling and research datasets are maintained separately in [ocr](https://github.com/astramark06-sudo/ocr).

No generated APKs, signing credentials, personal device exports or private logs should be committed. The historical debug-signature mismatch remains unresolved; a fresh build is not evidence of safe in-place update or physical device testing.
