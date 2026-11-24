# Repository working rules

This repository contains BatteryScope only. Read [BatteryScope/AGENTS.md](BatteryScope/AGENTS.md) before changing Android code. Preserve app identity, existing user data, telemetry semantics and local-first operation. Do not add privileged hardware controls. Keep generated APKs and private files out of Git.

The Android source is in `BatteryScope/`. Use `.github/workflows/batteryscope-android.yml` to build and test on `main`, pull requests, or manual dispatch. Historical build records in the imported documentation refer to the original OCR repository; verify the exact new commit and artifact before claiming a successful release. Do not change version or signing identity merely for repository migration.
