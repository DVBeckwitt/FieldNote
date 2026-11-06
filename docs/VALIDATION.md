# Beta 19 validation

Version **0.2.0-beta.19**, code **20**, was validated locally on 2026-11-06.
The [GitHub release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v0.2.0-beta.19)
provides the APK. Package: `com.fieldnote.app`; artifact size: **216,848 bytes**.

SHA-256: `d7081cd9996f915fe9e168b885c9baf2f4b1cde4a9c0dcf2e66a08eb647e246a`.

## Direct home-screen widget

The widget now sends an explicit immutable foreground-service request, instead
of opening MainActivity. Its capability survives process recreation. The service
resolves the last-created project without trusting caller-supplied project data,
keeps an active recording bound to its original project, and coalesces repeated
requests during capture. Missing setup shows a message and an explicit setup
notification. App updates refresh existing widget bindings.

The full gesture control remains a floating overlay activated by one widget tap;
it is not embedded in the static widget. Android documents [widget gesture
limitations](https://developer.android.com/develop/ui/views/appwidgets/overview#gestures)
and [widget-related foreground-service exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Three new native tests host the actual widget RemoteViews in a separate test APK
with a different Android UID. They verify delayed audio/photo capture with no
MainActivity lifecycle, last-created project selection, repeated taps, and missing
permission/project setup messages. Two additional tests verify service capability
identity and rejection of malformed requests. The existing widget test now checks
an immutable foreground-service PendingIntent.

## Standalone runtime acceptance

A separate check ran after instrumentation ended. The target app process was
killed while the external host remained visible, then relaunched only by tapping
the retained widget. Camera and microphone UID app-ops were set to `foreground`.
The overlay stayed idle for more than three minutes before touch started audio;
Android admitted the recording service with `UID_VISIBLE`, without MainActivity.
A linked audio/photo entry was saved. A second hold captured video, returned to
center, then captured a photo while keeping the same narration. All five new
AAC/JPEG/H.264 originals were extracted from the project ZIP and probed successfully.

This is a foreign-app RemoteViews host test, not an actual Smart Launcher or full
AppWidgetHost placement test. Instrumented success alone was not used to establish
normal background permission behavior.

## Build and tests

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
and SDK 35. The complete release/JVM/lint/native gate passed in **4m 51s**.
See [build instructions](../android/BUILDING.md) for the commands.

| Check | Result |
| --- | --- |
| Release assembly | Passed |
| JVM tests | 62 passed |
| Native API 36 emulator tests | 115 passed |
| Android total | **177 passed; 0 failures, errors, or skips** |
| Release lint | **0 errors, 63 warnings** |
| Signed release update from beta 18 | Passed; microphone grant preserved |
| Signed release launch | Passed; version and non-debuggable flag verified |

Existing capture, playback, marker, overlay, shortcut, note-save retry, storage,
ZIP publication, and security checks passed. No assertions or lint warnings were
suppressed. The retained browser prototype was unchanged and was not retested.

## APK and limits

APK Signature Scheme v2 verification passed with the existing beta certificate.
Packaged checks confirmed minimum API 26, target API 35, a non-debuggable release,
no INTERNET permission, and absence of the test host and test storage provider.
Public APK bytes and checksum are verified against the tested local artifact.

Native checks use the debug variant on Android 16 / API 36 with an emulated camera,
no host microphone audio, and NVIDIA host rendering. Signed-release checks cover
update, permission retention, and launch, not a complete data-migration exercise.
Actual Smart Launcher widget placement/update caching, physical Pixel/GrapheneOS,
older Android versions, hand-motion sensitivity, haptics, and all storage providers
remain unverified. Root is not used. See the [beta guide](../android/BETA.md).

Project ZIPs contain plaintext media and metadata. No exhaustive security guarantee
is claimed. There is no CI workflow; these are local results, with exact-commit
remote checks inventoried before and after publication.
