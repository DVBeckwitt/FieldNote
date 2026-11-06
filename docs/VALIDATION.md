# Beta 22 validation

Version **0.2.0-beta.22**, code **23**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.22)
provides the APK. Package: `com.fieldnote.app`; size: **220,412 bytes**.

SHA-256: `e182a0175533a0c74a23faa6d04e775f6e3857378bb5ddb4549a5a58a1c1fb7e`.

## Changes and regression coverage

**Record now** starts hands-free audio in the main recorder, using the latest-created
project. If a floating control is open, it closes before fresh audio starts. An
existing recording retains its original file and project and remains locked during
handoff. This avoids asynchronous overlay teardown stopping a new recording.
**Floating mic** is separately pinnable from Settings. Existing shortcut
authentication, history guards, and duplicate-launch protection remain.

The floating microphone has a six-dot drag handle and remembers its position.
Movement preserves locked narration and keeps room for the gesture targets. A
separate pointer owns movement; another finger cannot steal an active mic hold.
The new down-right X gesture closes the control and finalizes active audio/video,
including locked audio. This close direction is enabled only in the floating host.

Eight new JVM cases and ten native cases cover close boundaries and guidance,
shortcut publication and handoff, movement and reopening, pointer ownership,
locked-audio closure, and linked/standalone video closure. Native screenshots were
inspected for the handle and X target. Independent review found no further
consequential issue in the changed paths. No dependency update or broad refactor
was made.

## Final build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
SDK 35, and Android 16 / API 36. The final complete gate passed in **5m 22s**.
Commands are in [Building Fieldnote](../android/BUILDING.md).

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 70 passed |
| Native API 36 emulator tests | 135 passed |
| Final Android total | **205 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 63 warnings, 1 informational finding** |
| Signed release update from beta 21 | Passed; microphone grant preserved |
| Signed release launch | Passed; both Settings shortcut actions visible |

The final suite also covers existing capture, markers, playback, text-save retry,
search/tags, notes navigation, widget behavior, security controls, and project ZIP
publication. Source hashes and packaged APK bytes matched the tested build.
The unchanged browser prototype was not retested.

## Timing failures and follow-ups

The first full run had two missed subsequent touches after upward lock, affecting
the move-handle and marker tests. The test helper checked the 230 ms return
animation but could proceed before the 240 ms compact-window contraction finished.
It now also requires matching compact window/surface dimensions and completed
layout, and explicitly asserts that the next DOWN is received. All 15 focused
floating cases passed afterward. This corrects test readiness; it is not a claim
that every rapid physical interaction is proven reliable.

A second full run had one failure in the existing rapid-photo/audio-duration case.
Logs showed an AudioSource overrun, delayed system frames, and a first-track offset
of 628,775 microseconds, beyond the test's 500 ms startup allowance. This supports
runtime scheduling delay as a possible cause, without establishing a production
fix. Failure messages now include measured durations. The same case passed on a
fresh emulator, then all 135 native cases passed in the final complete run.
No duration tolerance, outcome assertion, timeout, or lint gate was relaxed, and
no automatic retry was added to the suite. Failed-run evidence was retained locally.

## APK and coverage limits

Signature Scheme v2 verification passed with the existing beta certificate.
Package checks confirmed code 23, minimum API 26, target API 35, a non-debuggable
release, no INTERNET permission, and absence of test-only components. The APK and
checksum identify the tested artifact; publication also verifies public download
bytes against that artifact.

Native tests use the debug variant, an emulated camera, no host microphone audio,
and NVIDIA host rendering. Signed-release checks cover upgrade, permission
retention, launch, and shortcut-action visibility, not a full data migration.
Physical Pixel/GrapheneOS, actual Smart Launcher placement, haptic feel, motion
sensitivity, older Android versions, and other storage providers remain unverified.
Root is not used. See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext media and metadata. This bounded pass is not an
exhaustive security guarantee. There is no CI workflow; results are local, with
exact-commit remote checks inventoried before and after publication.
