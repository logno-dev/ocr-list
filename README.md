# OCR List

A minimalist, native Android app that turns photos of written lists into editable checklists. Built with Kotlin and Material Components, with **ChatGPT photo recognition** and Google's **bundled ML Kit** offline scanner.

## What it does

- Take a photo using your camera app, or choose an image with Android's photo picker.
- Choose ChatGPT for handwriting, or on-device recognition for offline use.
- Keep the original image in private app storage. Where a reliable line reference exists, see a cropped photo in the editor and highlight it in the zoomable original. Otherwise, open the full photo directly from the editor.
- Edit names and quantities, add or delete items, rename or delete lists, and reset checkmarks for reuse.
- Normalize explicit quantities such as `2x milk` or `milk x2` into `milk × 2`. Leave ambiguous text such as `12 eggs` and `2% milk` intact.
- Keep checked items in their original positions.
- Automatically save lists locally, with light and dark themes following the device.
- Flag uncertain ChatGPT transcriptions for review. Saving a correction marks the item reviewed.
- Keep photos when recognition fails. **List options → Scan photo again** retries with the selected recognizer, creating a new list and preserving existing edits.

## Set up ChatGPT

1. Open **Recognition: on-device** on the home screen.
2. Tap **Continue with ChatGPT**. The app opens your system browser at OpenAI's sign-in page.
3. Sign in, select your workspace if prompted, and authorize **OCR List** to use your ChatGPT plan.
4. Tap **Open OCR List** on the callback page, or return to the app. Wait for the connection and model list to finish loading.
5. In recognition settings, check the selected account and model. **Choose model** and **Refresh available models** use your account's catalog; models that explicitly exclude image input are filtered out.
6. Scan a photo. A resized, orientation-corrected copy is sent to OpenAI; the untouched original remains on your device. Review the result before relying on it.

This integration follows OpenAI's documented **Sign in with ChatGPT / ChatGPT plan usage** flow for open-source and locally hosted apps. It registers OCR List under its own name and uses the public Responses endpoint, rather than borrowing another app's OAuth client or using ChatGPT's private backend. Availability depends on the account, plan, workspace, model, and OpenAI's preview rollout.

**No paid API key is used and there is no automatic fallback to separately billed API requests.** Requests consume your ChatGPT allowance. Open **ChatGPT usage & app access** in recognition settings and disable additional credits for OCR List if you only want included plan usage. If access or quota is unavailable, the app reports the problem and retains the photo. You can explicitly switch to the offline scanner.

Account registrations are kept separately. You can switch accounts, reauthorize an existing registration, or sign out. Access/refresh tokens are encrypted with an Android Keystore-backed AES-GCM key in no-backup storage. Sign-out attempts remote revocation and clears local tokens; if remote revocation cannot be confirmed, disconnect the app in ChatGPT Settings. The loopback browser listener is bound to `127.0.0.1`, validates OAuth state, uses PKCE and a nonce, and expires after ten minutes. ID-token signatures, issuer, audience, expiry, nonce, and returning-account identity are verified before activating a connection. Rotation keeps an in-progress sign-in alive; if Android kills the app during browser sign-in, reopen the app and start again.

### Recognition and privacy

On-device mode needs no account or model download and does not upload photos. Its ML Kit Latin-script model works best on printed text; handwriting accuracy is limited. ChatGPT mode needs internet access and sends photos to OpenAI under your account's applicable terms. Neither mode guarantees a perfect transcription.

ChatGPT is asked to preserve wording and flag uncertain text. Per-item crops are attached only when the item matches a unique normalized ML Kit line; the app never trusts model-generated coordinates or fuzzy matches. Unmatched items retain access to the full original photo. Every detected line—including headings—is editable. A scan with no recognized text still saves its photo.

Android backup and device-transfer backup are disabled. Uninstalling removes local lists, photos, and connections. Your chosen camera/photo provider is a separate app with its own settings.

Protocol references:
- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

### If connecting fails

Connection errors name the failing step (sign-in configuration, identity keys, token exchange, session refresh, or model loading), the server, and a safe diagnostic category such as DNS, TLS, or timeout. They do not include authorization codes, tokens, raw server responses, or credential-bearing URLs. Report that full message when troubleshooting.

The app fetches public identity configuration and signing keys before opening the browser so a failed identity-document download does not waste a one-time sign-in code. Network address fallback is enabled, but credential/photo POST bodies are marked one-shot and are not automatically replayed after they may have been sent. Certificate verification remains enabled.

If the app says **sign-in completed, but models could not load**, your connection was saved. Try **Recognition → Refresh available models** rather than repeating browser sign-in (unless the message specifically says your session was rejected). For DNS/connection problems, try Wi-Fi versus mobile data and check Private DNS, VPN, or per-app network restrictions. For TLS errors, also check the device clock. Browser connectivity alone does not confirm that the app can reach the same services.

## Build from the command line

Requires **JDK 17**, Android SDK command-line tools, and Android SDK platform 35. The Gradle wrapper is included; Android Studio is optional. Minimum device version: **Android 9 (API 28)**.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk" # macOS; use your SDK path
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
sdkmanager --licenses
./gradlew testDebugUnitTest lintDebug assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Alternatively, set `sdk.dir=/absolute/path/to/Android/sdk` in an untracked `local.properties` file. First builds need internet access to download dependencies. Offline recognition works without internet after installation.

With an emulator or device connected:

```sh
./gradlew connectedDebugAndroidTest
```

Unit tests cover list parsing, PKCE, callback validation, a real loopback callback, JWT verification/rejection, image request construction, streamed success/quota/error handling, and conservative photo references. Device tests cover Android Keystore encryption/tamper detection, recognition settings, failed-cloud photo retention, checklist editing/recreation, persistence, and real ML Kit recognition of a generated image.

Device tests reset the installed test app's lists and ChatGPT connections. Use an emulator or a dedicated test installation. Automated tests do not log into a real OpenAI account or consume a ChatGPT allowance; live account authorization, model availability, and handwritten accuracy need to be checked with your own account and photos.

## Release builds

GitHub Actions publishes exactly one installable **`OCR-List.apk`**. It tests, lints, builds a non-debuggable release, verifies the APK's signature, and uploads it on branch pushes, pull requests, manual runs, `v*` tags, and published releases. Tag builds create a GitHub release when needed and attach the APK. Rebuilding an existing release removes the old `OCR-List-debug.apk` and `OCR-List-unsigned.apk` assets after uploading the replacement.

```sh
git tag v1.0.0
git push origin v1.0.0
```

Use `vMAJOR.MINOR.PATCH` tags; the workflow derives a stable, increasing Android version code from that version. Keep minor and patch components below 1000.

### Obtainium and automatic signing

Add this repository's GitHub URL in Obtainium. New releases have just one APK asset, `OCR-List.apk`, so no debug/unsigned selection is needed. If you previously configured an APK filename filter, update it to `^OCR-List\.apk$`.

Android **cannot install a truly unsigned APK**. Builds are automatically signed using the same intentionally public development key in [`.github/ocr-list-development.keystore`](.github/ocr-list-development.keystore). No signing secrets, certificate setup, or Play Store registration is required. The public development password is `android`; it is not an account credential. Debug builds use the same identity for local testing.

Keep this file unchanged across releases so Obtainium updates can install in place. The signature is a development identity, not proof of publisher authenticity: use only your trusted repository's releases. See [development signing](.github/DEVELOPMENT-SIGNING.md).

**One-time migration:** earlier CI debug APKs used a different runner-generated key. Android may reject the first update with a signature mismatch. Preserve your existing lists/photos before uninstalling that old build, since uninstalling erases app data. After installing this version, subsequent tagged builds use the same key and update normally. There is no Android-supported way to replace an existing signature without the old key.

## Project map

- `MainActivity.kt`: list library, checklist, capture/import, and correction UI.
- `ListViewModel.kt`: scan lifecycle and state updates.
- `ListStore.kt`: explicit JSON serialization and atomic local saves.
- `ListModels.kt`: immutable list models and quantity parsing.
- `PhotoLoader.kt` / `PhotoView.kt`: orientation-aware decoding and zoomable references.
- `PhotoReferences.kt`: attaches only unique exact normalized line references.
- `chatgpt/`: OAuth/PKCE loopback login, verified identity, encrypted accounts, token refresh, model catalog, and streamed photo transcription.
- `.github/workflows/build.yml`: CLI build and release automation.

The original requirements are preserved in [`plan.md`](plan.md).
