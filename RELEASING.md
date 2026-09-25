# Releasing ENAGELYUCA

ENAGELYUCA APKs are published as **GitHub Release assets** — never committed to the repository
(`*.apk` is git-ignored). Installed copies find new versions through
**Settings → Check for Updates**, which reads the repository's latest published release.

## One-time setup

1. The repository is **https://github.com/enoluca22-dota2/YTD** (it keeps its original name;
   the product is ENAGELYUCA). `gradle.properties` points
   the in-app updater at it (`ytd.github.owner=enoluca22-dota2`, `ytd.github.repo=YTD`); the
   release workflow passes the same values for the repository it runs in.

2. **Add the signing secrets** (repository → Settings → Secrets and variables → Actions).
   They must be for the **same keystore** used for the APKs already installed
   (`keystore/ytd-release.jks`): Android refuses to update an app with an APK signed by a
   different key, and the app's own updater checks this before installing.
   The keystore file keeps its original name (`ytd-release.jks`); renaming the app doesn't change
   the key.

   | Secret              | Value                                                            |
   |---------------------|------------------------------------------------------------------|
   | `KEYSTORE_BASE64`   | `base64 -w0 keystore/ytd-release.jks` (PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\ytd-release.jks"))`) |
   | `KEYSTORE_PASSWORD` | the store password from `keystore.properties`                     |
   | `KEY_ALIAS`         | the key alias from `keystore.properties`                          |
   | `KEY_PASSWORD`      | the key password from `keystore.properties`                       |

   Passwords never go into `build.gradle.kts` or the repository.

## Publishing a version

1. Change the app, then set the new version in `app/build.gradle.kts` — the only line to edit:

   ```kotlin
   versionName = "1.2.0"
   ```

   `versionCode` is derived from it automatically (1.2.0 → 1002000), so it always increases.

2. Commit, tag with the same version and push the tag:

   ```bash
   git commit -am "Release 1.2.0"
   git push
   git tag v1.2.0
   git push origin v1.2.0
   ```

The workflow refuses a tag that doesn't match `versionName`.

`.github/workflows/release.yml` then:

1. checks out the tag, sets up JDK 21 and the Android SDK;
2. runs the unit tests;
3. checks the tag matches `versionName`, then builds signed release APKs
   (`versionCode` = major × 1,000,000 + minor × 1,000 + patch, so every release installs over
   the previous one);
4. verifies every APK's signature and writes `SHA256SUMS.txt`;
5. publishes the GitHub Release **v1.2.0** (using the workflow's automatic `github.token`) with release notes (commits since the previous tag
   plus the checksums) and these assets:

   | Asset                          | For                                       |
   |--------------------------------|-------------------------------------------|
   | `ENAGELYUCA-v1.2.0.apk`        | every device (universal)                  |
   | `ENAGELYUCA-v1.2.0-arm64-v8a.apk` | most phones                               |
   | `ENAGELYUCA-v1.2.0-armeabi-v7a.apk` | older 32-bit phones                       |
   | `ENAGELYUCA-v1.2.0-x86_64.apk` | emulators / x86 devices                   |
   | `SHA256SUMS.txt`               | checksums, verified by the in-app updater |

Tags with a suffix (`v1.3.0-beta.1`) are published as **pre-releases**; the app ignores
pre-releases and drafts, so only normal releases are offered as updates. You can edit the
release notes on GitHub afterwards — the update dialog shows them as published.

## How the app updates itself

* **Check for Updates** calls `GET https://api.github.com/repos/<owner>/<repo>/releases/latest`
  (public, no login) and compares versions numerically (`1.0.9 < 1.0.10`).
* An automatic check (can be switched off in Settings) runs at most once a day, and a version
  the user answered **Later** to isn't offered again automatically.
* **Update Now** downloads the APK for the device's ABI (or the universal one) into the app's
  cache with real progress, then verifies it: ZIP/APK format, SHA-256 from `SHA256SUMS.txt`,
  package name, a higher versionCode and the same signing certificate as the installed app.
* The verified APK is handed to Android's `PackageInstaller`; Android always shows its own
  confirmation. The first time, Android asks to allow "Install unknown apps" for ENAGELYUCA.

## Build check without releasing

Actions → **Release** → **Run workflow** runs the tests and builds the APKs without signing or
publishing anything — useful after changing the build.
