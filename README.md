# OCR List

A minimalist, native Android app that turns photos of written lists into editable checklists. Built with Kotlin, Material Components, and Google's **bundled ML Kit text recognition** model.

## What it does

- Take a photo using your camera app, or choose an image with Android's photo picker.
- Recognize each line as a checklist item, entirely on-device, including on the first launch.
- Keep the original image in private app storage. Each recognized item has a cropped photo reference in its editor; open the full photo with the item highlighted, pinch to zoom, and pan.
- Edit names and quantities, add or delete items, rename or delete lists, and reset checkmarks for reuse.
- Normalize explicit quantities such as `2x milk` or `milk x2` into `milk × 2`. Leave ambiguous text such as `12 eggs` and `2% milk` intact.
- Keep checked items in their original positions.
- Automatically save lists locally, with light and dark themes following the device.

No account, server, analytics integration, or internet permission. Android backup is disabled. Uninstalling the app removes its lists and photos. Your device's chosen camera/photo provider is a separate app with its own settings.

Recognition uses the Latin-script model. Clear, well-lit images work best; handwriting accuracy varies. Every detected line—including a heading—becomes an editable item. Review the results against the saved photo. A scan with no recognized text still saves its photo so you can add items manually.

## Build from the command line

Requires **JDK 17**, Android SDK command-line tools, and Android SDK platform 35. The Gradle wrapper is included; Android Studio is optional. Minimum device version: **Android 9 (API 28)**.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk" # macOS; use your SDK path
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
sdkmanager --licenses
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, set `sdk.dir=/absolute/path/to/Android/sdk` in an untracked `local.properties` file. First builds need internet access to download dependencies; the installed app does not.

With an emulator or device connected:

```sh
./gradlew connectedDebugAndroidTest
```

The device tests exercise checklist editing and recreation, persistence, and real ML Kit recognition of a generated image, including quantities, reading order, saved originals, and per-line photo bounds. Unit tests cover conservative list/quantity parsing. Handwritten accuracy and camera behavior should also be checked on your own phone.

## Release builds

GitHub Actions builds and uploads APK artifacts on branch pushes, pull requests, manual runs, `v*` tags, and published releases. Pushing a tag such as `v1.0.0` builds the app and creates a GitHub release with APKs attached. Publishing an existing release also builds its tag and attaches the artifacts.

```sh
git tag v1.0.0
git push origin v1.0.0
```

Use `vMAJOR.MINOR.PATCH` tags; the workflow derives a stable, increasing Android version code from that version. Keep minor and patch components below 1000.

### Signing

Without signing secrets, CI produces an **installable debug APK** and an **unsigned release APK**. Unsigned APKs cannot be installed directly. Debug APKs from different CI runs may use different debug keys; use a consistent release key for updatable builds.

To publish a signed `OCR-List.apk`, create and securely keep a release key:

```sh
keytool -genkeypair -v -keystore ocr-list.jks -alias ocr-list \
  -keyalg RSA -keysize 4096 -validity 10000
```

Add these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded contents of the keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `ocr-list` (or your chosen alias) |
| `ANDROID_KEY_PASSWORD` | Key password |

For local signed builds, export `SIGNING_STORE_FILE` (absolute path), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`, then run `./gradlew assembleRelease`. Optionally set `VERSION_NAME` and `VERSION_CODE`. Never commit the keystore or passwords.

## Project map

- `MainActivity.kt`: list library, checklist, capture/import, and correction UI.
- `ListViewModel.kt`: scan lifecycle and state updates.
- `ListStore.kt`: explicit JSON serialization and atomic local saves.
- `ListModels.kt`: immutable list models and quantity parsing.
- `PhotoLoader.kt` / `PhotoView.kt`: orientation-aware decoding and zoomable references.
- `.github/workflows/build.yml`: CLI build and release automation.

The original requirements are preserved in [`plan.md`](plan.md).
