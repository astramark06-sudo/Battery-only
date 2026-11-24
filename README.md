# BatteryScope — standalone Android repository

BatteryScope 1.15 (versionCode 16) is a local-first battery and device telemetry application for Android. Application ID: `com.batteryscope`.

- [App documentation](BatteryScope/README.md)
- [Project status](BatteryScope/PROJECT_STATUS.md)
- [Release checklist](BatteryScope/RELEASE_CHECKLIST.md)
- [Android build workflow](.github/workflows/batteryscope-android.yml)

## Build
GitHub Actions builds the debug APK and runs JVM tests on changes to the Android project on `main`, on relevant pull requests, or by manual dispatch. Local build: Java 17, Gradle 8.9 and Android SDK 35; from `BatteryScope/`, run `gradle :app:testDebugUnitTest :app:assembleDebug`.

## Migration provenance
The 37 original Android project files were imported unchanged from [ocr commit c3cc95c](https://github.com/astramark06-sudo/ocr/tree/c3cc95cf2dfa29e3c85af02c2aa65fc769635b7d/BatteryScope). The workflow trigger was then adapted to this repository's `main` branch. Historical documentation references the original repository and CI runs; those are historical evidence, not new builds. The original commit history remains in `ocr`. OCR, Python, AI tooling and research datasets are maintained separately in [ocr](https://github.com/astramark06-sudo/ocr).

No generated APKs, signing credentials, personal device exports or private logs should be committed. The historical debug-signature mismatch remains unresolved; a fresh build is not evidence of safe in-place update or physical device testing.
