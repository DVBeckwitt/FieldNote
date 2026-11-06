# Beta 17 validation

Version **0.2.0-beta.17**, code **18**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.17)
provides the APK. Package: `com.fieldnote.app`; artifact size: **215,192 bytes**.

SHA-256: `6a817ab55b196f67ad2fae216fe09a2d946130f0bdaab2e050b9418020d3e4d4`.

## Build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
and SDK 35. The complete release/JVM/lint/native gate passed in 4m 5s.
See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 62 passed |
| Native API 36 emulator tests | 108 passed |
| Android total | **170 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 63 warnings** |
| Signed release install and launch | Passed; version and non-debuggable flag verified |

Nine detector tests cover separated shakes, cooldown and quiet rearming, input
axes/signs, isolated bumps, sustained motion, invalid samples, and stale sessions.
Tests for retired training and directional features were replaced with simple-shake
coverage. Native tests invoke the recognized-shake callback to start and stop real
AAC recording, retain linked touch photos and markers, verify foreground/settings
gating, and check preference migration. They do not simulate a physical phone shake.

Existing native coverage for launcher shortcuts, floating touch controls, camera
transitions, original media decoding, text drafts, project ZIP publication, search,
markers, storage, and security also passed. The retained browser prototype was
unchanged and was not retested for this release.

## APK and visual checks

APK Signature Scheme v2 verification passed with the same certificate as earlier
betas. Packaged checks confirmed minimum API 26, target API 35, and a non-debuggable
release. The six retired motion/training classes and training labels are absent;
the new shake detector is present. The signed APK installed and launched on the
emulator. Actual dark/light Record and simplified Settings screenshots were inspected.

## Emulator recovery and limits

Initial native runs were interrupted by Windows access violations in the emulator
host using SwiftShader. The same source and APK passed the focused shake case and
the complete gate after switching to verified NVIDIA host rendering. No assertions
or application code were changed to obtain that pass.

Native behavior tests use the debug variant on Android 16 / API 36 with an emulated
camera and no host microphone audio. They do not establish physical Pixel/GrapheneOS
behavior, hand-motion sensitivity, haptic feel, or every Android/storage-provider
combination. Lint warnings remain; none were suppressed to pass.

Shake recording operates while the Record screen is open and focused. Settings,
notes, Files, dialogs, background use, and the floating overlay suspend it. The
floating control retains its touch gestures and normal Android permission
requirements. Root is not used. See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext original media and metadata. No app-level encryption
or exhaustive security guarantee is claimed. There is currently no CI workflow;
these are local validation results, with remote checks inventoried at publication.
