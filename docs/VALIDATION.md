# Beta 23 validation

Version **0.2.0-beta.23**, code **24**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.23)
provides the APK. Package: `com.fieldnote.app`; size: **225,540 bytes**.

SHA-256: `c3e420b48810077a736168ae7aef7273b0bd181b318248f4c610bbba9c6429b8`.

## Changes and corrections

The **Recorder** widget starts and stops audio inside the launcher using the
latest-created project. It requires neither camera nor overlay permission and
never automatically opens an Activity for setup. Existing **Record now** and
**Floating mic** shortcuts remain available. Active microphones use a purple
gradient and halo; app and floating halos animate when system animations allow.
The widget uses a static purple glow, consistent with its RemoteViews layout.

The sweep found and corrected these lifecycle cases:

- A stale purple widget could create a foreground service just to reject Stop,
  causing an Android foreground-promotion crash. Stop now uses a private,
  authenticated broadcast bound to the exact entry and recording-session token.
  It cannot start a new original or stop a later original in the same entry.
- An ordinary service PendingIntent passed instrumentation but was rejected by
  Android after a cold launcher tap. Start now uses a direct foreground-service
  PendingIntent. A brief permission-free admission phase precedes microphone
  promotion and project I/O; rejected setup ends it safely. This uses Android's
  [short service type](https://developer.android.com/develop/background-work/services/fgs/service-types#short-service)
  on API 34 and later and the documented
  [widget/visible-app exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions).
- Stopping narration through the widget or audio notification now finalizes its
  linked floating video, even while the original mic hold remains down.
- A floating control for another project remains purple while audio is active
  and can close via down-right swipe. Its other gestures cannot capture, mark,
  or stop that other project's recording.

Independent reviews covered session ownership, foreground admission, private
component boundaries, linked-video cleanup, and visual state. No dependency
update or broad refactor was made.

## Final build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
compile/target SDK 35, and Android 16 / API 36. Commands are documented in
[Building Fieldnote](../android/BUILDING.md). Validation ran
`assembleRelease`, `testDebugUnitTest`, `lintRelease`, and
`connectedDebugAndroidTest` with offline dependencies and the dedicated emulator.
The full native run completed 144 cases with one widget-host teardown failure.
After correcting only that test class, all seven widget cases passed in a Gradle
rerun using `-Pandroid.testInstrumentationRunnerArguments.class=com.fieldnote.app.DirectWidgetInstrumentedTest`,
together with release assembly, JVM tests, and lint. Hash comparison confirmed
production sources and every unaffected test class were unchanged. Results below
combine the 137 unaffected native cases with those seven freshly verified cases.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 70 passed |
| Native API 36 emulator tests | 144 passed |
| Final Android total | **214 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 66 warnings, 1 informational finding** |
| Signed release update from beta 22 | Passed; microphone grant preserved |
| Signed release launch | Passed; folder, shortcut, and Recorder-widget settings verified |

The suite includes existing capture, repeated photo/video gestures, markers,
playback, text-save retry, search/tags, notes navigation, security controls, and
project ZIP publication. New native cases use a real foreign-UID AppWidgetHost
and real encoded media, including rapid cancellation and cached Stop actions.
Source hashes remained unchanged through the final gate and artifact checks.
The unchanged browser prototype was not retested.

## Cold widget acceptance

After instrumentation exited, the target process was killed and camera permission
revoked; overlay permission was also denied. A tap in the still-visible foreign
widget host started a real microphone foreground service (type `0x80`) without
opening Fieldnote or a floating window. The widget turned purple. A second tap
stopped audio and published a second distinct AAC original into the same project
ZIP; its stored byte count and encoded duration were checked.

Actual `RECORD_AUDIO` runtime permission was then revoked outside instrumentation.
The next tap stayed on the host, showed the setup notification, and left no
recording service or new original. No AndroidRuntime crash appeared during these
cold checks. Purple widget and held/locked app screenshots were visually inspected.

An earlier AppOps-denial test did not model this correctly because instrumentation
could still record. It was replaced by the actual cold runtime-permission check,
not a relaxed assertion. The test host also now resolves the numeric Android user
before widget binding: API 36 rejected `--user current` as the unresolved ID -2.
The first complete run also exposed a teardown routing error: ActivityScenario's
helper could sit above the widget host, so CLOSE created a second instance.
Adding CLEAR_TOP targets the original host and preserves its destruction assertion.
Failed-run evidence was retained locally; no outcome or duration tolerance was
relaxed and no automatic retry was added.

## APK and coverage limits

Signature Scheme v2 verification passed with the existing beta certificate.
Package inspection confirmed code 24, minimum API 26, target API 35, a
non-debuggable release, no INTERNET permission, and no test-only components.
Publication verifies the public APK bytes against the tested artifact.

Native tests use the debug variant, an emulated camera, no host microphone audio,
and NVIDIA host rendering. Signed-release checks cover upgrade, permission
retention, launch, and settings, not a full data migration. Cold widget checks
use the debug APK without instrumentation running in its process.
Physical Pixel/GrapheneOS, actual Smart Launcher placement, haptic feel, motion
sensitivity, older Android versions, and other storage providers remain unverified.
Root is not used. See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext media and metadata. This bounded pass is not an
exhaustive security guarantee. There is no CI workflow; results are local, with
exact-commit remote checks inventoried before and after publication.
