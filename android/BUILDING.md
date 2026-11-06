# Building Fieldnote

Use JDK 17 or a compatible newer JDK, Android SDK Platform 35, and the included
Gradle wrapper. The beta was verified on Windows with JDK 21. The project pins
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

The APK is written to `app/build/outputs/apk/release/app-release.apk`.
JVM test results are under `app/build/test-results/testDebugUnitTest/`; the lint
report is `app/build/reports/lint-results-release.html`. On a Unix-like shell,
use `./gradlew` with the same tasks; the recorded beta validation used Windows.

## Native tests

Use a dedicated test emulator. These tests install app/test packages and can
remove them during cleanup; do not use a device containing notes you need to
keep. The beta's native checks used Android 16 / API 36 with an emulated rear
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

## Signing and installation

The current release configuration is non-debuggable but uses Gradle's local
debug signing configuration. The published beta uses the maintainer's existing
local certificate; a build on another machine will normally use a different
certificate and cannot update that installed APK. No signing key is included in
the repository. Use a separate test device for your own builds, or retain the
same signing identity for updates to your own installations. Keep an installed
app with pending captures and its data intact.

For the downloadable build and normal use, see the [beta guide](BETA.md).
The [validation summary](../docs/VALIDATION.md) distinguishes emulator tests,
signed-release checks, and physical-device limits. There is currently no CI
workflow; these commands are the local validation path.

## Original browser prototype

The earlier browser prototype is kept in `prototype/`. From the repository root,
with Node 24 (the validated runtime), run `npm run check`, `npm test`, and
`npm run build`. `npm start` serves the prototype locally. The generated
`prototype/preview.html` is intentionally not tracked.
