# Beta 20 validation

Version **0.2.0-beta.20**, code **21**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.20)
provides the APK. Package: `com.fieldnote.app`; artifact size: **217,000 bytes**.

SHA-256: `5c18da2d207f304158eb6097c5b1f64b9d43b45aba3cb2ed28b5cb18cb8b7345`.

## Project files beside Settings

The Files tab and horizontal tab navigation were removed. A folder button beside
Settings offers to open the granted storage folder, open the project ZIP in a
compatible external app, or share the ZIP. The Record surface and finger-following
notes sheet remain, including search, tags, markers, and microphone gestures.

Folder viewing uses Android's `ACTION_VIEW` directory intent. External browsing
receives temporary read-only access scoped to the user-granted storage tree;
ZIP viewing receives temporary read access to the exact selected document.
An imported standalone ZIP has no inferred parent folder. Opening/sharing the ZIP
is blocked while capture or queued publication is active. Viewer absence and
revoked access have explicit error messages.

Five new intent tests cover opaque document IDs, grant scope, imported ZIPs,
tree-only locations, and invalid input. A new native integration test verifies
header placement, menu actions, and external intent dispatch without replacing
Record. Existing navigation tests now check that horizontal swipes stay on Record;
second-finger header taps cannot interrupt a held recording. Notes filtering,
activity recreation, and shake gating still have native coverage.

## Standalone runtime acceptance

Outside instrumentation, a new test project was created through Android's actual
folder picker. **Open storage folder** launched Android Files at that granted
folder with the project ZIP visible; it did not open a folder-selection dialog.
Returning through Files restored Fieldnote. This emulator has no app registered
for direct ZIP viewing, so **Open project ZIP** was verified to show its useful
no-viewer message. Direct ZIP dispatch and grants were verified by native tests,
not by a third-party ZIP viewer. Actual dark and light screenshots were inspected;
the README now shows the tab-free native UI.

## Build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
and SDK 35. The complete release/JVM/lint/native gate passed in **5m 2s**.
See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 62 passed |
| Native API 36 emulator tests | 121 passed |
| Android total | **183 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 63 warnings** |
| Signed release update from beta 19 | Passed; microphone grant preserved |
| Signed release launch | Passed; folder button visible, tabs absent |

Existing capture, playback, marker, overlay, widget, shortcut, note-save retry,
storage, ZIP publication, and security checks passed. Tested source hashes were
unchanged at packaging. No assertions or lint warnings were suppressed. The
retained browser prototype was unchanged and was not retested.

## APK and limits

APK Signature Scheme v2 verification passed with the existing beta certificate.
Packaged checks confirmed version code 21, minimum API 26, target API 35, a
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
