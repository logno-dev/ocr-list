# Automatic development signing

`ocr-list-development.keystore` is an **intentionally public development key**, not a private production credential. Its alias is `ocr-list-development`; its store and key passwords are `android`.

Android rejects truly unsigned APKs. Gradle uses this fixed development identity for local debug builds and non-debuggable release builds, so CI can publish one installable `OCR-List.apk` without signing secrets. Keeping this same file across releases allows Obtainium to install updates without signature mismatches. Do not regenerate it for each build or rely on an expiring CI cache.

Because this key is public, its signature is not proof that a build came from this repository. Install updates only from your trusted repository's releases. This is intended for personal sideload distribution, not a protected production/Play Store identity.

Earlier CI builds used randomly generated runner debug keys. The first move to this identity may require uninstalling the old app. Uninstalling deletes local lists, photos, and connections; preserve anything you need before doing that. Later builds using this identity can update in place.
