# Beta 21 validation

Version **0.2.0-beta.21**, code **22**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.21)
provides the APK. Package: `com.fieldnote.app`; artifact size: **217,312 bytes**.

SHA-256: `4fe6b12634298c70f2d200cf0f6efc937bb7245535a7b5d8a4d51562549dd4d8`.

## Confirmed bugs and corrections

Both recording controls previously treated the last finger leaving the microphone
as the end of a hold. If a second finger remained after the original one lifted,
audio could continue and the remaining finger could take over capture gestures.
MainActivity and FloatingRecorderService now retain the original pointer ID,
follow its current and batched coordinates, ignore secondary releases, and end
the hold when its owner lifts. Cancellation/lifecycle paths clear that ownership.
Explicit upward locks still keep their recording running. Coordinate conversion
retains API 26 compatibility and follows Android's
[pointer ID contract](https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi).

A second-finger tap on Settings could also open a focus-stealing dialog during a
held recording. Settings now uses the same held-touch guard as the Files button.

Four new native regression cases failed before the fixes and passed afterward.
They cover owner/secondary release, reordered pointer arrays, prevention of
secondary camera gestures, Settings focus, floating lock preservation, and saved
encoded audio. Main-screen cases dispatch multi-pointer events through the actual
view hierarchy; floating cases inject input through Android's input dispatcher.
Independent review found no further actionable navigation, capture, or storage
issue within this bounded pass. No broad refactor or dependency update was made.

## Final build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
and SDK 35. The final complete release/JVM/lint/native gate passed in **6m 19s**.
See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 62 passed |
| Native API 36 emulator tests | 125 passed |
| Final Android total | **187 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 63 warnings** |
| Signed release update from beta 20 | Passed; microphone grant preserved |
| Signed release launch | Passed; folder button visible, tabs absent |

Capture, playback, markers, overlay/widget, shortcuts, note-save retry, search,
tags, note-sheet gestures, storage, ZIP publication, and existing security checks
passed in the final run. Tested source hashes matched at packaging. Assertions,
timeouts, and lint checks were not relaxed. The unchanged browser prototype was
not retested.

## Intermittent test result

The initial full run had **one failure out of 125 native tests**:
`FloatingRecorderInstrumentedTest.upwardLockAndDiagonalMarkerSurviveReleaseUntilCenterTapStops`
timed out waiting for a marker after locking audio. The cause remains unconfirmed.
The isolated test passed, all nine floating tests passed together, and the original
preceding-widget plus floating sequence passed all 12 tests. The final full run
also passed. These were three bounded diagnostic follow-ups, not automatic retries
added to the suite. Failure-only pointer/window/marker diagnostics were retained;
no production timing change or relaxed assertion was used to conceal the result.
A possible overlay-resize readiness gap was identified during review but was not
established as the cause or reported as fixed.

## APK and coverage limits

APK Signature Scheme v2 verification passed with the existing beta certificate.
Packaged checks confirmed version code 22, minimum API 26, target API 35, a
non-debuggable release, no INTERNET permission, and absence of test-only components.
Public APK bytes and checksum are verified against the tested local artifact.

Native checks use the debug variant on Android 16 / API 36 with an emulated camera,
no host microphone audio, and NVIDIA host rendering. Signed-release checks cover
update, permission retention, and launch, not a complete data-migration exercise.
Physical Pixel/GrapheneOS, actual Smart Launcher placement, older Android versions,
haptics, motion sensitivity, and other file explorers/storage providers remain
unverified. Root is not used. See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext media and metadata. No exhaustive security guarantee
is claimed. There is no CI workflow; these are local results, with exact-commit
remote checks inventoried before and after publication.
