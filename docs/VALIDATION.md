# 0.2.0 validation

Version **0.2.0**, code **25**, was validated locally on 2026-11-06.
The [stable release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0)
provides the APK. Package: `com.fieldnote.app`; size: **208,136 bytes**.

SHA-256: `d87b136c483b8e44b0b27c45289659f5f80f417ecf8bcbec78c5684a1c2ab6b1`.

## Review and changes

The [review report](REVIEW.md) covers the publisher UI-stall fix, measured
notes-list optimization, idle playback polling, removal of the unused camera
screen and pause state, security boundaries, and explicit release signing.
Project storage and historical metadata remain compatible.

The publisher contention test failed against the original implementation because
the main thread waited for the journal lock. It passed after queue scans moved
to the worker, followed by the complete native suite. The performance comparison
passed the same five correctness tests before and after; its synthetic helper
timings are not claims about phone UI latency.

## Final build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
compile/target SDK 35, and Android 16 / API 36. From `android/`, with the dedicated
emulator selected and dependencies cached, the complete gate was:

```powershell
.\gradlew.bat --offline --no-daemon -PfieldnoteUseExistingBetaKey=true :app:assembleRelease :app:testDebugUnitTest :app:lintRelease :app:connectedDebugAndroidTest
```

| Check | Result |
| --- | --- |
| Signed release assembly | Passed |
| JVM tests | 70 passed |
| Complete native API 36 emulator suite | 147 passed |
| Android total | **217 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 58 warnings, 1 informational finding** |
| Default unsigned release build | Passed; signature absence confirmed |
| Partial signing configuration | Rejected with the expected configuration error |
| Signed update from beta 23 | Passed; microphone permission retained |
| Signed release launch | Passed; folder, shortcut, and Recorder-widget settings verified |

The full gate completed in 7m 8s. Source/build-input hashes remained unchanged
through artifact verification. Coverage includes actual encoded audio/video,
repeated camera gestures, markers, continuous narration during text entry,
playback, search/tags, navigation, ZIP publication/recovery, security controls,
and foreign-UID widget actions. Lint warnings remain, principally UI text,
compatibility, accessibility/custom-view, and styling guidance; this release
does not claim to eliminate every warning. The unchanged browser prototype
was not retested. See [BUILDING.md](../android/BUILDING.md) for setup.

## Cold widget acceptance

After fixture instrumentation exited, camera permission was revoked and the
target process killed. Overlay permission was also denied. A tap in a foreign
widget host started a real microphone foreground service, changed the circle
to purple, and kept the host foreground without opening Fieldnote or an overlay.
A second tap stopped audio and published a second distinct AAC original into
the same ZIP. Stored byte counts and encoded duration were checked, and the
purple widget screenshot was visually inspected.

Revoking the actual `RECORD_AUDIO` runtime grant outside instrumentation caused
the next tap to show the setup notification. The host remained foreground;
no recording service or new original remained. These checks passed on the
final build after the recorder cleanup.

## APK, security, and coverage limits

Signature Scheme v2 verification passed with the existing beta certificate
(SHA-256 `5145841e0feb89981109b16d0a9de86b8aa09a39b094b879d761c628faf0ab82`).
Package inspection confirmed code 25, minimum API 26, target API 35, a
non-debuggable release, no INTERNET permission, and no test-only components.
The publication process verifies downloaded APK bytes against the tested file.

Native tests use the debug variant, an emulated rear camera, no host microphone
audio, and NVIDIA host rendering. Cold widget checks run without instrumentation
in the target process. Signed-release checks cover installation, upgrade,
permission retention, launch, and settings; they are not a full data-migration
exercise. Physical Pixel/GrapheneOS, actual Smart Launcher placement, haptic
feel, motion sensitivity, older Android releases, other operating systems,
and other storage providers remain unverified. Root is not used.

Project ZIPs contain plaintext media and metadata. The bounded security review
is not an exhaustive guarantee. The repository has no CI workflow: results
are local, with exact-commit remote workflows, runs, checks, and statuses
inventoried before and after publication. See the [user guide](../android/GUIDE.md).
