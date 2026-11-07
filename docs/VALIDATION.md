# 1.0.0 validation

Fieldnote **1.0.0**, phone code **26** and Fieldnote Voice code **2**, was
validated locally on 2026-11-07. The [release](https://github.com/DVBeckwitt/FieldNote/releases/tag/v1.0.0)
contains separate signed, non-debuggable APKs for the phone and watch.

## Review and fixes

The current-project Recorder widget, paired Bluetooth receiver/importer, retry
destination binding and deduplication, watch queue, launcher toggle and recorder
service were reviewed. The release includes three focused repairs found during
this pass:

- Settings now distinguishes the widget's current project from the newest-created
  project used by Record now and Floating mic.
- Watch audio saves before it exceeds the 128 MiB Bluetooth transfer bound. The
  recorder limit leaves container headroom and handles both approaching/reached
  callbacks through the ordinary token-checked save/send path.
- Each watch capture gets a unique temporary original. A failed final rename or
  interrupted session cannot be overwritten by the next recording.

The existing video regression now waits for actual native playback and displayed
seek progress beyond its backward-seek target, instead of assuming screenshot
wall time advances playback. It retains the same deadline and seek assertions.

## Final checks

Environment: Windows, JDK 21, Gradle 8.14.6, Android Gradle Plugin 8.6.0,
compile/target SDK 35, dedicated Android 16/API 36 emulator with SwiftShader,
emulated rear camera, no host audio and no snapshots. The configured Node 24
runtime (24.22.1) was used for the unchanged browser prototype.

| Check | Result |
| --- | --- |
| Complete phone native suite | 151 passed, zero failures/errors/skips; 5m 35s |
| Phone JVM tests | 70 passed |
| Shared Bluetooth protocol JVM tests | 3 passed |
| Watch native lifecycle/encoder tests | 4 passed |
| Prototype syntax, model tests and build | Passed; 7 model tests |
| Total automated tests | **235 passed** |
| Signed phone and watch release assembly | Passed |
| Phone release lint | 0 errors, 58 warnings, 1 informational finding |
| Watch release lint | 0 errors, 2 Wear Recents task-flag warnings |
| Signed emulator upgrade from 0.2.0 to 1.0.0 | Passed; microphone grant retained; launch, version and capture settings verified |
| Source/build-input consistency | All 97 recorded inputs unchanged through final verification |

The complete phone suite used `:app:connectedDebugAndroidTest`. The subsequent
combined gate completed in 48 seconds with:

```powershell
.\gradlew.bat --offline --no-daemon -PfieldnoteUseExistingBetaKey=true :app:assembleRelease :app:testDebugUnitTest :app:lintRelease :wear:assembleRelease :wear:lintRelease :watchlink:test :wear:connectedDebugAndroidTest
```

All instrumentation ran on the dedicated emulator, never on the user's phone or
watch. An initial attempt ended after 64 passing tests when its detached emulator
process disappeared. The unchanged complete suite passed after the emulator was
launched in a persistent process session. No assertions or deadlines were relaxed.
The four new watch tests use real encoded audio and exercise Home, notification
reopen, Activity recreation, notification Stop, stale Stop rejection, discard,
and failed-rename preservation. The file-size callbacks are simulated; a multi-hour
recording was not used to reach the size limit physically.

## Signed builds on physical devices

Both final APKs updated the Pixel 8 Pro and Watch4 Classic SM-R895U in place.
Microphone and Bluetooth grants remained, along with watch notification permission,
connection settings and the older watch queue item. No app data was cleared.

On the signed watch build, an injected top-button double press began recording.
Home followed by twelve seconds in display **Dozing** state left the microphone
foreground service active. Opening the recording screen preserved the session;
on-screen Stop sent one **24.503-second** original into the disposable project.
The signed phone's actual Recorder widget started/stopped in Smart Launcher and
sent a **6.933-second** original into that same selected project. The launcher
remained foreground. FFmpeg decoded both files, with nonzero samples in every
complete one-second window (24 and 6 respectively). The project ZIP passed CRC
verification; all five older audio originals had unchanged SHA-256 hashes.
The phone was restored to `today`, with watch reception enabled.

The wearer's earlier physical Knock knock start/stop confirmation and locked-phone
Bluetooth transfer are recorded below. This release reran the mapped top button,
background recording and transfer, and the physical widget. It did not repeat the
wearer's gesture detection or a locked-phone transfer. Samsung's built-in Knock
knock does not dispatch while PIN-locked. PIN protection and Wallet settings were
preserved. Display doze is not a claim of a long deep-sleep endurance test.

## Release artifacts and limits

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `Fieldnote-1.0.0.apk` | 217906 | `907b22b06dd8fab3cfef94d97982654ea082851fa93b9072763a66bb2d1881db` |
| `Fieldnote-Voice-1.0.0.apk` | 59526 | `e5f6015b1314bcfdacf5fe7e1c1e3c0f2f9da59a4681b2175e8cd8e508559189` |

Both pass APK Signature Scheme v2 verification with the existing installation
certificate `5145841e0feb89981109b16d0a9de86b8aa09a39b094b879d761c628faf0ab82`.
Package/version/minimum/target SDK checks passed. Neither release is debuggable,
requests INTERNET permission, or contains the test components. Release publication
verifies the public downloads byte-for-byte against these tested artifacts.

The repository has no CI workflow. Exact-commit workflows, runs, check runs and
statuses are inventoried before and after publication; local results do not imply
hosted CI coverage. Lint warnings remain. Other Android/Wear OS versions, storage
providers and launchers, Wi-Fi-off transfer, long recordings and all permission/
process interruption combinations are not established by these tests. The existing
archive complexity limit and pending-save caveat described below remain unchanged.
The [build instructions](../android/BUILDING.md) and [user guide](../android/GUIDE.md)
describe setup and runtime boundaries.

---

The following are historical records for the earlier 0.2.0 release and local
iterations. Their artifact hashes, test interruptions and unpublished status refer
to those earlier builds, not the 1.0.0 results above.

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

## 2026-11-07 local widget and watch update

This unpublished working-tree build changes the Recorder widget to bind each new
recording to the project selected when the widget is tapped. Changing selection
after that tap does not move an active recording. Record now and Floating mic
retain their separate latest-created-project behavior. Fieldnote Voice now
toggles recording on a fresh launcher invocation, supporting the Watch4 top-button
double press and its Knock knock app shortcut. Ordinary resume and non-MAIN
intents do not toggle capture.

Validation uses the same Windows/JDK 21 toolchain described above. The signed
phone update and watch debug APK were installed in place on a Pixel 8 Pro and
Galaxy Watch4 Classic SM-R895U. No user app data was cleared. Both APK signatures
verified, and the phone retained the existing beta certificate.

| Check | Result |
| --- | --- |
| Phone release assembly, 70 phone JVM tests, 3 shared-protocol JVM tests | Passed |
| Phone release lint | 0 errors, 58 existing warnings |
| Watch debug assembly and release lint | Passed; no lint issues |
| Complete API 36 native regression suite | No clean complete pass; final run lost its emulator after 64 passes |
| Widget and watch-import native regressions | All 11 passed in completed runs on both renderers |
| Shortcut suites after timing investigation | 25 passed unchanged |
| Corrected video capture/playback/seek regression | Passed in the final run before emulator disconnection |
| Physical Recorder widget | Start/stop stayed in Smart Launcher; saved one decodable AAC original |
| Physical watch top-button shortcut | Injected double press started; second double press stopped and sent |
| Foreground watch launcher re-entry | MAIN started, intervening VIEW did not stop, next MAIN stopped and sent |
| Locked-phone reception | Watch displayed Sent to Fieldnote while Pixel deviceLocked was true |
| Knock knock | Assigned to Fieldnote Voice; wearer reported both start and stop worked |

The new native regressions exercise current selection versus latest creation,
selection changes during startup and recording, absent or malformed selection,
stale Stop actions, interrupted watch transfers, retry destination binding,
duplicate delivery, altered retry bytes/headers, and invalid audio. The first
full-suite attempt was interrupted before producing a final result. Its partial
results are not a complete pass. The first completed rerun had 149 passes and two
failures: a five-second main-loop timeout in Record now, followed by a widget
idle-state assertion because the timed-out Stop and teardown callbacks had been
abandoned. The same log records an 11.534-second HWUI render stall and about 690
skipped frames in both the launcher and Fieldnote, supporting an emulator
renderer stall rather than a destination-selection defect. Both affected test
classes then passed (25 tests) with no changes to code, assertions, or timeouts.
The next full host-GPU run had 150 passes and a different shortcut timeout,
with about 1,780 skipped frames and 29–30-second HWUI frames in both Fieldnote
and the launcher. The dedicated emulator was switched from `-gpu host` to the
SDK's supported `-gpu swiftshader` renderer for one full-suite rerun. The app,
API 36 system image, emulated camera, and test assertions remain unchanged.
That run removed the shortcut timeouts but exposed one existing test assumption:
video playback was required to reach only 100 ms before a screenshot delay was
assumed to move its media position beyond 600 ms. The test now explicitly waits
for both native playback and displayed seek progress beyond 600 ms, retaining
the same 15-second deadline and the paused/resumed seek assertions. No product
code changed for this test repair. The corrected video regression passed in the
next software-rendered run, but the emulator process exited during the 65th test.
Gradle reported `device 'emulator-5568' not found`, 64 completed passing tests,
and an interrupted shake test; it could not execute the remaining cases or its
normal device cleanup. The emulator was no longer running afterward. Repeated
host-GPU stalls and the later software-emulator exit prevent certification of a
single clean complete native run. The physical-device results and targeted
passing cases above do not replace that missing full-suite result.

The disposable physical test ZIP contained four distinct entries: the earlier
watch test plus a phone-widget clip and two additional automated watch clips.
ZIP CRC verification and FFmpeg decoding of every audio original passed. The
phone's current project was restored to `today`, with the watch receiver active.
Wi-Fi remained enabled for ADB; a Wi-Fi-off hardware repetition and long-duration
recordings were not tested. These checks do not establish every phone/firmware,
launcher, storage provider, or motion-detection condition.

An existing global publication warning was present before this update. The older
`test.zip` index has 249,862 structural tokens, near the unchanged 250,000-token
limit; `today.zip` has 20,716. Both archives passed CRC checks. A pending save can
exceed that limit even though the current project's ZIP is healthy. Existing
archives and queued originals were preserved; this update does not migrate or
raise the project complexity limit.

Samsung's Watch4 does not expose the Tizen Payments only lock setting. Device
PIN protection was retained to preserve Samsung Pay; wrist detection was already
enabled. No payment transaction or lock bypass was attempted.

Phone APK SHA-256:
`fabe8e511e8ecc6229f0b56c60e86875734bb3c14f793c8b9737ba17082fadaf`.
Watch APK SHA-256:
`20e759eaf8f60a7de462bce32395da98050c30f3da346de3fdc360984d81e7b7`.
No commit, push, or release was made. There is no CI workflow; these are local
and physical-device results.

## Watch background recording update

The watch companion starts a microphone foreground service from the visible
recorder. Home, display sleep, and reopening the screen leave the session active.
Explicit Stop finalizes the original and hands it to the existing Bluetooth
uploader. The recording notification offers an additional Stop control.

On the physical Watch4 Classic, the top-button shortcut started the recorder.
The same active file grew from 159,072 bytes in the app to 264,211 bytes on Home,
then to 435,184 bytes after ten seconds with `mWakefulness=Asleep`. Reopening the
recording screen showed the running timer. On-screen Stop sent one new original
to the disposable Fieldnote project. FFmpeg decoded 34.67 seconds, with nonzero
samples in all 34 complete one-second windows. The watch service ended and its
older queued clip remained. The phone was restored to `today`.

Watch debug assembly, release lint, and the three shared protocol tests passed.
Lint reported two Wear Recents warnings on the recording notification's task
flags. The physical test exercised on-screen Stop. Samsung's installed Quick
Launch handler explicitly skips Knock knock when the keyguard is active.
