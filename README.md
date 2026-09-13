# NextPhoto

[English](README.md) · [繁體中文](README.zh-TW.md) · [简体中文](README.zh-CN.md)

[Download the latest APK](https://github.com/rayqwe1234/NextPhoto/releases/latest) · [Release history](https://github.com/rayqwe1234/NextPhoto/releases)

A native Android gallery for photos stored in Nextcloud. Available in English, Traditional Chinese, and Simplified Chinese, with large headings, date groups, immersive photo grids, and light and dark themes. No ads, paid features, or third-party analytics.

**Current version: 0.1.7, signed Release build.** Primary compatibility targets are Nextcloud 34.0.3 and Android 16; Android 10 is the minimum. Live-server integration and background backup on physical devices still require validation. Start with test photos.

This is an independently developed client, not an official Nextcloud product. It needs no additional backend and does not change your server configuration.

This release renames Nextcloud Photo to NextPhoto and introduces a white aperture icon on a blue background. The application ID and signing certificate remain compatible with earlier releases. Existing encrypted albums continue to use `Nextcloud Photo Vault`, and exported files continue to use `Download/Nextcloud Photo`.

## Languages

The interface follows the first language in your system language list: Traditional Chinese (including Taiwan, Hong Kong, and Macao), Simplified Chinese, or English. All other languages use English, even if Chinese is your second system language. Reopen the app after changing your system language. File names, album names, and existing backup paths are not translated or renamed. Server messages and previously saved errors retain their original text.

## Installation and sign-in

1. Install `NextPhoto-v0.1.7.apk`. If Android prompts you, allow your file manager to install apps. The signing certificate is unchanged, so you can update versions 0.1.0 / 0.1.1 / 0.1.2 / 0.1.3 / 0.1.4 / 0.1.5 / 0.1.6 directly while keeping your account and data.
2. Enter your Nextcloud **HTTPS** URL. Custom ports and installation subpaths are supported, for example `https://cloud.example.com:8443/nextcloud`.
3. Tap **Connect to my Nextcloud**, authorize the app in your browser, and return to the app. Your main account password is not required by the app.
4. Photos appear progressively during initial indexing. Choose a cloud folder to scan in Settings. If Photos is unavailable, the file-based photo library and folder browser remain available; the Albums tab explains why.

Certificate verification cannot be disabled, and neither self-signed certificate bypasses nor plain HTTP are supported. If your server uses a private certificate, configure Android to trust it first.

## Features and controls

| Screen | Features |
| --- | --- |
| Photos | Daily groups, date jump, 2–5-column grids, file and album name search, video / favorite / offline filters |
| Viewer | Swipe between photos, pinch to zoom, load originals, play videos, inspect EXIF metadata |
| Selection | Long-press photos to add them to a Photos album or queue offline downloads |
| Albums | Create, rename, add and remove members in sync with Nextcloud Photos; browse folders separately |
| Encrypted albums | Encrypt photos, videos, file names, album names, and thumbnails; unlock with a password or fingerprint; clear the decrypted session on exit |
| More actions | Move and rename files, download to your phone, system sharing, read-only public links with passwords and expiry dates, revoke links |
| Editor | Drag the crop frame, resize corners and edges, use a rule-of-thirds grid, rotate continuously from −180° to +180°, mirror, adjust brightness; upload a separate full-resolution JPEG without overwriting the original |
| Transfers | Persistent upload / download queue, progress, cancellation, errors and retries, clear history |
| Settings | Storage quota, last sync, scan scope, auto backup, network restrictions, appearance, cache, sign-out |

Capture dates use server metadata when available and otherwise fall back to modification dates. Reading EXIF can fill in missing capture dates later. Initial indexing does not download large numbers of originals just to obtain EXIF data.

Drag inside the crop frame to move it, or drag its corners and edges to resize it. The area outside the frame is dimmed. Drag the rotation slider to adjust the angle continuously, or tap **Reset angle**. Cropping uses the rotated photo's coordinates so the saved image matches the preview. Crop away blank edges introduced by rotation as needed. **Reset** restores all editing settings.

**Save to phone Downloads** exports to `Download/Nextcloud Photo`; **Save offline** stores files in the app's private storage. Signing out does not delete exported files. Clearing an offline copy affects only local app data.

**Clear history** in Transfers removes completed, failed, and cancelled list entries while keeping queued and active transfers. It does not delete photos and retains backup deduplication records to avoid uploading completed backups again. The button is disabled when there is no history to clear.

## Encrypted albums

Create an album under **Albums → Encrypted albums**. Import encrypted copies from your phone or cloud library while keeping the originals. Albums lock immediately on exit or when the app goes into the background; decrypted photos and videos are not written to disk. The fingerprint entry is always visible on the locked album screen. For initial setup, unlock with the album password first, then tap **Enable fingerprint unlock** at the top. If no fingerprint is enrolled or the device is unsupported, the app explains the reason.

Images up to 32 MiB each and videos up to 32 GiB each are supported. Videos use encrypted chunk uploads and on-demand decrypted playback with seeking, without a plaintext disk cache. Encrypted transfers require the app to stay in the foreground and do not support background resumption. Nextcloud Photos cannot preview the encrypted files on the web. Keep your album password and a complete backup of the cloud folder safe.

See [encrypted album usage, format, and cleanup behavior](docs/ENCRYPTED_ALBUMS.md) (Traditional Chinese).

## Manual uploads and auto backup

- Manual upload: tap **+** at the top right of Photos and select photos or videos with Android's system picker.
- Auto backup is off by default. Select a source folder on your phone in Settings, save the cloud upload destination, and enable auto backup. Files are organized by `year/month`.
- Backup uses system folder access rather than requiring access to the entire phone gallery. Denying or cancelling access does not start backup. Revoked access produces an error and asks you to select the folder again.
- Transfers use Wi-Fi or other unmetered networks by default. Android schedules background work; battery saving, force-stopping the app, or manufacturer restrictions may delay backups. Use **Scan for backups now** to request a scan.
- Backup is one-way: deleting a phone file does not delete its cloud copy. The same source, size, modification time, and destination are not scheduled twice. Name collisions receive a new name with an identifier.
- Files larger than 10 MiB use 10 MiB chunks. Final assembly forbids overwriting, and retries can reuse uploaded chunks. Nextcloud's expiry mechanism handles unfinished temporary server uploads.
- The app stages uploads locally, so your phone needs sufficient free space. A transfer is marked complete only after server confirmation. If the destination already exists after a process interruption, the app compares SHA-256 hashes to avoid falsely reporting success or uploading a duplicate.

## Deletion and privacy

- **Remove from this album** removes only the Photos album membership, keeping the original photo.
- **Delete cloud original** requires confirmation. Recovery and retention depend on your server's trash support. Deletion and moves use ETag conditions to avoid stale operations on files updated by someone else.
- Android Keystore protects login credentials. System backup and device transfer exclude private app data; network requests do not send credentials to other domains.
- The thumbnail cache is limited to 1 GB. Explicitly saved offline originals are counted separately. Signing out stops transfers and clears credentials, the index, and private app copies.

## Known limitations

- One signed-in account at a time. The app uses Photos' personal album interface; Memories integration, face recognition, maps, and smart albums are outside the first release's scope.
- Format support depends on Android decoders and server preview settings. Unsupported files can be downloaded and opened in another app. There is no built-in video transcoding, RAW editing, or video trimming.
- Editing produces JPEG files and does not preserve motion-photo, HDR, or animation data. Originals above 40 megapixels or beyond available device memory prompt you to use an external editor instead of silently reducing resolution.
- Missing server thumbnails and insufficient permissions are reported explicitly; another account's cached files are never substituted.
- Auto backup depends on Android scheduling and is not an always-running, real-time sync service. Reinstalling the app does not preserve previous backup history.
- See the [test report](docs/TESTING.md) (Traditional Chinese) for completed tests and pending acceptance checks.

## Build from source

Requirements: JDK **21** (needed by Android 16 Robolectric tests), Android SDK Platform 36, and Build Tools 36.0.0. The project uses Gradle 9.4.1, Android Gradle Plugin 9.2.0, and Kotlin / Compose compiler 2.3.10. Dependency versions are pinned in the Gradle configuration.

Set `JAVA_HOME` and `ANDROID_HOME`, or configure `sdk.dir` in an untracked `local.properties`, then run:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `assembleRelease` shrinks code and resources; without a distribution key, it produces an unsigned APK.

Local distribution keys live in the untracked `.signing/` directory. **Back up this directory securely: future updates require the same key.** Keys are not included in source archives or Git. For your own builds, create `.signing/signing.properties` with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`.

## Source layout

- `account`: Login Flow v2 credential storage.
- `network`: Nextcloud DAV / OCS communication, URL validation, and streaming XML parsing.
- `data`: Room, Paging, incremental media indexing, and album membership.
- `transfer`: WorkManager backup and resumable transfers.
- `ui`: Compose library, viewer, editor, transfers, and settings.

Protocol references and third-party components are listed in [THIRD_PARTY.md](THIRD_PARTY.md) (Traditional Chinese).
