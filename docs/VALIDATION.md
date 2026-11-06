# Beta 16 validation

Version **0.2.0-beta.16**, code **17**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.16)
provides the APK. Package: `com.fieldnote.app`; artifact size: **232,340 bytes**.

SHA-256: `4640074478c6010a2c273b1d3b15c4f6f96618620601519cb8c633f43aafc311`.

## Build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
and SDK 35. The complete release/JVM/lint/native gate passed in 4m 22s.
See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 86 passed |
| Native API 36 emulator tests | 109 passed |
| Android total | **195 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 76 warnings** |
| Retained browser prototype | Syntax checks, 7 tests, and build passed with Node 24.22.1 |

New native coverage verifies authenticated launcher actions, last-created project
selection, duplicate/replayed requests, real encoded audio, missing projects,
background cancellation, and recording during blocked ZIP publication. A cold
launch regression verifies that the first UI refresh cannot block cached quick
capture while waiting for window focus.

Overlay tests inject real touch gestures over the launcher while Main is stopped.
They verify held audio, repeated linked photos, video-to-photo transitions, lock,
markers, text submission, Hide cleanup, and standalone video with embedded audio.
Original AAC/MP4/JPEG files are opened and decoded. Three draft tests verify that
an older save cannot erase a newly reopened or edited text draft. Existing capture,
storage, publication, motion, filtering, camera, and security tests also passed.

## APK and visual checks

APK Signature Scheme v2 verification passed with the same certificate as earlier
betas. Packaged checks confirmed minimum API 26, target API 35, non-debuggable
release, no INTERNET permission or test storage provider, private recorder services
and widget receiver, and `BIND_JOB_SERVICE` protection for the publisher.
The signed APK installed successfully. Shell UID 2000 was denied starts of both
private recorder services; a forged shortcut action opened the app without
starting its recording service. These are focused checks, not a full release-UI
or upgrade exercise.

Actual emulator screenshots of dark/light Record screens, notes search/tags/markers,
and the idle, held, and text-entry overlay were inspected. The text editor and
keyboard were visible while audio remained active. The shared microphone retains its
fixed origin and blue recording feedback. A screenshot-only settling delay was
added for dialog review; all seven affected overlay tests passed again afterward,
as did the separate retained-screenshot text test. These repeats are not counted
as additional unique tests in the table.

## Limits

Native behavior tests use the debug variant on Android 16 / API 36 with an emulated
camera and no host microphone audio. They do not establish physical Pixel/GrapheneOS
behavior, launcher pin-confirmation UI, hand-motion sensitivity, or haptic feel.
Actual OS permission-denial UI and every Android/storage-provider combination are
not covered. The stale permission callback test verifies cancellation, not an OS
denial. Lint retains 76 warnings, versus 68 in beta 15; none are suppressed to pass.

The floating control requires Android's overlay permission and ordinary microphone/
camera grants. Root is not used. Touch gestures work in the overlay; phone-motion
gestures remain in the main app. The home-screen widget opens the overlay and does
not itself receive custom drag gestures. See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext original media and metadata. No app-level encryption
or exhaustive security guarantee is claimed. There is currently no CI workflow;
these are local validation results, with remote checks inventoried at publication.
