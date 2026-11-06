# Beta 15 validation

Version **0.2.0-beta.15**, code **16**, was validated locally on 2026-11-05.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.15)
provides the APK. Package: `com.fieldnote.app`; artifact size: **208,152 bytes**.

SHA-256: `b4019211b4cd43f8605adac78355aae079d803340052adada20e3771d203e510`.

## Build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0, and
SDK 35. The final combined release/JVM/lint/native gate completed successfully
in 3m 1s. See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 86 passed |
| Native API 36 emulator tests | 76 passed |
| Total | **162 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 68 warnings** |

Native coverage includes capture/UI, project storage, JSON resource limits,
publication queues, motion settings, note filtering, inline camera behavior,
security configuration, and gesture guides. The new JSON cases reject excessive
nesting/token counts and preserve source data, including legacy imports with an
older backup. The explicit backup configuration removed the prior backup-rules
lint warning; remaining warning IDs/messages were unchanged.

The delivered APK matches the final build byte for byte. Hash checks confirmed
that the 58-file pre-change baseline remained intact and all 14 recorded final
source files still matched the tested implementation. This records source
provenance, not a claim of reproducible builds across machines.

Before publication, the incremental Android build/JVM/lint gate passed again
with its implementation and APK hash unchanged. The retained browser prototype
also passed syntax checks, all seven model tests, and its standalone build with
Node 24.22.1. The system's older Node 16 lacks the required test runner.

## Signed APK and UI checks

APK Signature Scheme v2 verification passed with the same certificate as prior
betas. Packaged checks confirmed version code/name, minimum API 26, target API 35,
non-debuggable release, no INTERNET permission, no test storage provider, and
`BIND_JOB_SERVICE` protection for the publisher. Backup resources contain nine
legacy exclusions and 18 cloud/device-transfer exclusions.

The final signed APK was installed on the test emulator. An ungranted provider
query and a recording-service start from shell UID 2000 were both denied.
These two probes do not cover the full URI grant/revoke lifecycle.

Debug-variant screenshots of dark and light Record screens and notes search,
tags, and markers were inspected; no layout regression was observed in those
three screens.

## Limits

Native UI tests used the debug variant on an Android 16 / API 36 emulator.
Signed-release checks covered the artifact and two access-denial probes, not a
full UI or upgrade exercise. Physical Pixel/GrapheneOS behavior, hand-motion
sensitivity, and haptic feel remain untested. Results do not cover every Android
version, storage provider, interruption, or manufacturer's backup/transfer mode.

Project ZIPs contain plaintext metadata and original media. No app-level
encryption or exhaustive security assessment is claimed. There is currently no
CI workflow; the results above are local validation. See the [beta guide](../android/BETA.md)
for supported behavior, storage limits, and recovery guidance.
