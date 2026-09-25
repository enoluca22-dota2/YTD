# ENAGELYUCA

**YouTube & TikTok Downloader**

**ENAGELYUCA** is an Android downloader focused on **YouTube** and **TikTok**: it saves videos
and audio to your phone, shows the qualities a video really offers, downloads in the
background with real progress, and keeps itself up to date from this repository's GitHub
Releases.

The name is built from **EN**o, **AG**ron, **EL**io, **Y**ehlen and **LUCA**.

ENAGELYUCA supports YouTube and TikTok only. Any other link gets "Unsupported website. YouTube and
TikTok are currently supported."

## Features

### YouTube
- Videos, Shorts, live replays and `youtu.be` links.
- The real format list for each video (up to 2160p where available), video + audio merged
  automatically.
- Audio: original quality, **MP3** (VBR, 320 / 256 / 192 / 128 kbps) with title, artist and
  cover art, or **M4A** without re-encoding.

### Playlists
- `playlist?list=…` links open a picker: title, item count, thumbnails, durations and positions.
- Select All / Deselect All / individual items, one quality for all, **Download Selected**.
- A video opened from a playlist (`watch?v=…&list=…`) asks **Current video** or **Playlist**.
- Private or removed items are marked and skipped.
- Every item is its own download: one failure doesn't stop the others, and you can retry just
  that item.

### TikTok
- `tiktok.com/@user/video/…` links and the `vm.` / `vt.` share links, with the real qualities.

### Queue and background downloads
- A persistent queue: downloads continue in the background and survive the app being closed.
- **Real progress**: percentage, downloaded / total size, speed and time left, taken from
  yt-dlp's own byte counts. Unknown sizes show an indeterminate bar instead of a guess.
- Clear states: Queued, Fetching info, Downloading, Processing, Completed, Failed, Cancelled,
  Paused. Pause, resume, cancel and retry per item, plus queue-wide actions.
- Notifications: "Downloading · Title · 47%", or "Downloading playlist 4 / 18".
- History of completed, failed and cancelled downloads, with where each file was saved.

### Android integration
- **Share** a YouTube or TikTok link from any app to ENAGELYUCA, or choose **Open with ENAGELYUCA**.
  Links keep all their parameters (for example a playlist position).
- Clipboard detection offers a copied link when you open the app.
- Files are saved with Android's storage APIs to `Movies/ENAGELYUCA` and `Music/ENAGELYUCA`, or a
  folder you choose. (Downloads made before the rename stay in `Movies/YTD` / `Music/YTD`.)

### Design
- Themes: Glass, Premium Glass, Light, Dark and System.

## Installation

1. Open the [latest release](https://github.com/enoluca22-dota2/YTD/releases/latest).
2. Download **`ENAGELYUCA-vX.Y.Z.apk`**. It works on every device. The smaller `…-arm64-v8a.apk` fits
   most phones.
3. Open the file on your phone and allow installing apps from that source when Android asks.

Requires Android 8.0 (API 26) or newer.

## In-app updates

**Settings → Check for Updates** asks GitHub for the latest release of
`enoluca22-dota2/YTD` (the repository keeps its original name). No login is needed.

- If a newer version exists, you see the new and current versions, the release notes and the
  size, with **Update Now** and **Later**.
- **Update Now** downloads the APK for your device with real progress, then checks it:
  - that it is a valid APK;
  - its SHA-256 checksum from `SHA256SUMS.txt`;
  - that it is the same app, with a higher version;
  - that it is signed with the same key as the installed app.
- Android's own installer then asks you to confirm. Nothing is installed silently.
- An optional automatic check runs at most once a day, and a version you answered **Later**
  to isn't offered again automatically.

## Building

Requirements: Android Studio (recent), JDK 17+, Android SDK 37.

```bash
./gradlew :app:assembleDebug        # debug build (package com.enoluca.ytd.debug)
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleRelease      # release APKs, one per ABI plus a universal one
```

Release APKs are written to `app/build/outputs/apk/release/`.

Release signing reads `keystore.properties` in the project root (git-ignored) or the
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment variables.
Without them the release APK is unsigned. Keystores and passwords are never committed.

```properties
# keystore.properties (local only, never committed)
storeFile=keystore/ytd-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## Releasing

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`):

1. Set the version in `app/build.gradle.kts`: `versionName = "1.2.0"`. `versionCode` follows
   automatically.
2. Commit and push, then tag and push the tag:

   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```

3. The workflow runs the tests, builds signed APKs and publishes the GitHub Release `v1.2.0`
   with these assets:
   - `ENAGELYUCA-v1.2.0.apk` (universal)
   - the per-ABI APKs
   - `SHA256SUMS.txt`
   - release notes built from the commits

Installed apps then offer the update.

The one-time signing setup and details are in [RELEASING.md](RELEASING.md).

## Development

| Area | Where |
|---|---|
| Link detection (YouTube / TikTok only) | `data/platform/Platform.kt`, `data/analyzer/` |
| yt-dlp integration, formats, playlists | `data/provider/` |
| Download queue, states, progress, notifications | `download/` (`DownloadEngine`, `ProgressTracker`) |
| Database (Room, with migrations) | `data/local/db/` |
| In-app updates (GitHub Releases) | `update/` |
| UI (Jetpack Compose, themes, Glass) | `ui/` |

- Downloads use [yt-dlp](https://github.com/yt-dlp/yt-dlp) through
  [youtubedl-android](https://github.com/JunkFood02/youtubedl-android). yt-dlp updates itself
  at most once a day, never while a download is running.
- Tests: `app/src/test` (JVM) and `app/src/androidTest` (on a device or emulator, including
  queue and progress tests).

## Support the project

If ENAGELYUCA is useful to you, you can support its development on Buy Me a Coffee. The link will be
added here soon.

## Disclaimer

Only download content you have the right to download. Respect the terms of service of YouTube
and TikTok and the rights of creators.
