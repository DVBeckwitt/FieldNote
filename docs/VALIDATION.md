# Beta 18 validation

Version **0.2.0-beta.18**, code **19**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.18)
provides the APK. Package: `com.fieldnote.app`; artifact size: **215,336 bytes**.

SHA-256: `67616307135ce3a59425e657ee143c278711ed7a52364fdde6df2a23bd2baf9b`.

## Bug review and correction

A slow note save left its text, name, and tags editable after taking the submitted
snapshot. Typing during ZIP publication could therefore disappear when the dialog
closed. The new regression reproduced this in the unfixed code against a blocked
storage provider. The fix disables all three fields during submission and restores
them on failure, keeping the draft available for correction and retry.

The regression now passes. It checks input/button state during a blocked ZIP write,
persisted content and tags, recovery after an injected write failure, and a successful
retry preserving the entry identity and creation time. A second regression verifies
that shaking to stop held audio/video cancels the touch hold: late finger movement
and release cannot restart recording or take an extra photo. It decodes the saved
AAC/MP4 originals and checks their entry links and capture times.

Independent source review of shake lifecycle, camera transitions, floating capture,
ZIP recovery, annotations, search, and shortcuts found no other confirmed defect.

## Build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
and SDK 35. The complete release/JVM/lint/native gate passed in **4m 30s**.
See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 62 passed |
| Native API 36 emulator tests | 110 passed |
| Android total | **172 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 63 warnings** |
| Signed release update from beta 17 | Passed; microphone grant preserved |
| Signed release launch | Passed; packaged version and non-debuggable flag verified |

Both new native tests passed in a focused run and again in the full gate. Existing
capture, playback, marker, overlay, shortcut, storage, ZIP publication, and security
checks also passed. The retained browser prototype was unchanged and was not
retested. Lint warnings remain; none were suppressed to pass.

## APK and runtime limits

APK Signature Scheme v2 verification passed with the same certificate as earlier
betas. Packaged checks confirmed minimum API 26, target API 35, and a non-debuggable
release. The published APK must match the verified local artifact and checksum.

Native behavior tests use the debug variant on Android 16 / API 36 with an emulated
camera, no host microphone audio, and verified NVIDIA host rendering. Signed-release
checks cover package update, permission retention, and launch, not a complete user-data
migration exercise. Physical Pixel/GrapheneOS behavior, hand-motion sensitivity,
haptic feel, and every Android/storage-provider combination remain unverified.
Shake tests use synthetic traces or recognized-shake callbacks, not physical motion.

Shake recording remains limited to the focused Record screen. The floating control
retains touch gestures and normal Android permission requirements. Root is not used.
See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext original media and metadata. No app-level encryption
or exhaustive security guarantee is claimed. There is currently no CI workflow;
these are local validation results, with exact-commit remote checks inventoried
before and after publication.
