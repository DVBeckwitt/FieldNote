# Building Fieldnote

Use JDK 17 or a compatible newer JDK, Android SDK Platform 35, and the included
Gradle wrapper. Release validation uses Windows with JDK 21. The project pins
Gradle **8.14.6** and Android Gradle Plugin **8.6.0**; Java source/target compatibility
is 17, minimum Android API is 26, and compile/target API is 35.

Configure your SDK through Android Studio or `ANDROID_HOME`, and set `JAVA_HOME`
to your selected JDK. Keep machine-specific `local.properties` out of version
control. The first build needs access to the configured dependency repositories
and the checksum-pinned Gradle distribution. Use `--offline` only after the
required distribution and dependencies are cached.

## Build, unit tests, and lint

From the repository's `android/` directory, in PowerShell:

```powershell
.\gradlew.bat --no-daemon :app:assembleRelease :app:testDebugUnitTest :app:lintRelease
```

Without a signing configuration, this writes the unsigned
`app/build/outputs/apk/release/app-release-unsigned.apk`.
JVM test results are under `app/build/test-results/testDebugUnitTest/`; the lint
report is `app/build/reports/lint-results-release.html`. On a Unix-like shell,
use `./gradlew` with the same tasks; the recorded release validation used Windows.

## Wear OS companion

Build the separate Wear OS app and check the shared Bluetooth framing with:

```powershell
.\gradlew.bat --no-daemon :app:assembleRelease :wear:assembleRelease :wear:lintRelease :watchlink:test
```

The unsigned watch APK is `wear/build/outputs/apk/release/wear-release-unsigned.apk`.
The signing configuration below applies to both apps and produces
`wear/build/outputs/apk/release/wear-release.apk` when configured. Both release
variants are non-debuggable. `:wear:assembleDebug` builds a development APK.
Install the signed release on the
watch using [Wear OS wireless debugging](https://developer.android.com/training/wearables/get-started/debugging).
Use `adb -s <watch-serial> install -r <path-to-Fieldnote-Voice-1.0.0.apk>`
after connecting to the watch. The phone must have a Fieldnote build that
supports watch reception. An existing phone installation needs an update signed
with the same certificate. See the [watch setup](GUIDE.md#record-from-a-galaxy-watch).

Use a real paired watch and phone to verify the hardware shortcut, microphone,
and Bluetooth transfer with Wi-Fi off. Test with the phone locked. Then stop
the receiver, record again, and use **Send saved** after re-enabling it. Confirm
one playable audio entry per recording and the correct destination project.

## Native tests

Use a dedicated test emulator. These tests install app/test packages and can
remove them during cleanup; do not use a device containing notes you need to
keep. Native checks use Android 16 / API 36 with an emulated rear
camera, no host audio, and snapshots disabled.

Select your test target explicitly before running instrumentation:

```powershell
adb devices
$env:ANDROID_SERIAL = '<your-test-emulator-serial>'
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest
```

Replace the serial placeholder with the dedicated target shown by `adb devices`.
Instrumentation uses the debug variant. Results are written under
`app/build/outputs/androidTest-results/connected/`.
The watch recorder lifecycle suite uses `:wear:connectedDebugAndroidTest` on a
dedicated API 30+ test device or emulator. It also installs and removes test
packages; never run it against a watch with recordings you need to keep.

## Signing and installation

Release builds are non-debuggable and unsigned by default. To sign with your
own existing keystore, supply all four environment variables:

- `FIELDNOTE_KEYSTORE_PATH`
- `FIELDNOTE_KEYSTORE_PASSWORD`
- `FIELDNOTE_KEY_ALIAS`
- `FIELDNOTE_KEY_PASSWORD`

Keep credentials outside the repository and command output. A partial signing
configuration fails immediately. With signing configured, `assembleRelease`
writes `app/build/outputs/apk/release/app-release.apk`.

Published Fieldnote updates retain the maintainer's existing beta certificate
so current installations can upgrade. That deliberate compatibility build uses
`-PfieldnoteUseExistingBetaKey=true`; it requires the existing local keystore and
refuses to generate a replacement. It cannot be combined with the environment
configuration above. A different machine's key cannot update the published APK.
No signing key is included in the repository. Use a separate test device for
your own signing identity, and keep installed apps with pending captures intact.

For the downloadable build and normal use, see the [user guide](GUIDE.md).
The [validation summary](../docs/VALIDATION.md) distinguishes emulator tests,
signed-release checks, and physical-device limits. There is currently no CI
workflow; these commands are the local validation path.

## Original browser prototype

The earlier browser prototype is kept in `prototype/`. From the repository root,
with Node 24 (the validated runtime), run `npm run check`, `npm test`, and
`npm run build`. `npm start` serves the prototype locally. The generated
`prototype/preview.html` is intentionally not tracked.
