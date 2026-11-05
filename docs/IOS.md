# StickerDock on iPhone

The iPhone app is the same Kotlin Multiplatform library and Compose UI as Android (`app/shared`), plus an iOS
host (`app/shared/src/iosMain`) and a thin SwiftUI shell (`app/iosApp`). Nothing here is verified on an iPhone yet;
`docs/STATUS.md` gets iOS entries only after a passing `ios.yml` run or a device check.

## Files

| Path | What |
| --- | --- |
| `app/shared/src/iosMain/kotlin/app/stickerdock/ios/MainViewController.kt` | `MainViewController()` (Compose UI + iOS host) and `openDocument(url)` for "Open in StickerDock"; toast-style message overlay |
| `.../ios/IosGraph.kt` | Database driver, `Library`, sticker files, prefs (NSUserDefaults), Discover client from Info.plist, event log |
| `.../ios/IosHost.kt` | `HostActions` for iOS: WhatsApp, imports (queue + progress + cancel), share, backup, diagnostics, maintenance |
| `.../ios/Presenter.kt` | Document pickers, share sheet, alerts |
| `.../ios/NativeIo.kt`, `Images.kt` | NSData ↔ ByteArray, memory-mapped file reader, POSIX file writer, folders; tray PNG and dHash via UIKit/CoreGraphics |
| `.../ios/whatsapp/` | Pasteboard payload builder + pre-flight checks (pure Kotlin), sender (UIPasteboard + `whatsapp://stickerPack`) |
| `.../ios/imports/` | Archive import (all Android formats), folder import with the resumable journal, WebP checks (pure Kotlin) |
| `.../ios/export/Archives.kt` | `.wastickers` pack share and library backup writers (pure Kotlin) |
| `.../ios/zip/` | ZIP reader/writer (pure Kotlin) and raw-DEFLATE through the system zlib |
| `app/shared/src/iosTest/` | Simulator tests: ZIP (incl. a `java.util.zip` fixture), imports, backup round trip, WhatsApp JSON, UIKit image work |
| `app/iosApp/project.yml` | XcodeGen spec (`xcodegen generate` → `StickerDock.xcodeproj`, not committed) |
| `app/iosApp/iosApp/` | `StickerDockApp.swift`, `ContentView.swift`, `Info.plist`, `PrivacyInfo.xcprivacy` |
| `.github/workflows/ios.yml` | macOS CI: framework link, unsigned simulator app build, simulator tests |

## How the iPhone host works

- **Library**: SQLite `Library/Application Support/databases/library.db` (SQLDelight native driver, foreign keys on,
  same schema and migrations as Android). Sticker files `Library/Application Support/stickers/<id>.webp`, written
  atomically with `NSFileProtectionCompleteUntilFirstUserAuthentication`. Ids are lowercase UUIDs, clock is epoch ms.
  Week-old soft-deleted stickers are purged at launch; followed Discover packs refresh at launch at most every 12 h.
- **Prefs**: `NSUserDefaults`, keys prefixed `settings.`.
- **Discover**: Info.plist `StickerDockDiscoverURL` / `StickerDockDiscoverKey`, filled from the build settings
  `STICKERDOCK_DISCOVER_URL` / `STICKERDOCK_DISCOVER_KEY` (empty by default = offline). Pass them on the
  `xcodebuild` command line or in an untracked `*.local.xcconfig`; never commit keys. (In an xcconfig, `//` starts a
  comment: write `https:/$()/host`.)
- **Messages**: `showMessage` shows a short overlay above the bottom bar (Android uses a Toast).
- **Keyboard**: the three keyboard actions show "planned"; `keyboardEnabled` is always false on iPhone.

### Imports

| Entry | Picker | Notes |
| --- | --- | --- |
| ZIP, .wastickers or backup | Files picker (ZIP and `.wastickers`), copies | Several files are imported one after another |
| Photos and images | Files picker (images), copies | Up to 100; only already-valid WebP stickers (see gaps) |
| From this phone's WhatsApp | Files picker (folder), in place | iPhone apps cannot open WhatsApp's own sticker folder: a one-time note explains this; pick a folder copied from an Android phone or a computer |
| Open in StickerDock | Files, Mail, share sheet | `.wastickers` and `.zip`; files are read in place (security-scoped) |

Archive formats are the Android ones, unchanged: `manifest.json` with schema `stickerdock.library.v2` (backup),
`stickerdock.pack.v2` (pack share), StickerDock v1 backups (`packs[]`, v1 MATURE packs become vault packs) and
plain ZIPs of images (grouped by WhatsApp EXIF pack name, else folder). Same limits: 5 000 entries, 5 MB per file,
300 MB per archive. Differences from Android: oversized or damaged entries are skipped instead of failing the whole
archive, and a shared pack's `rating` (18+) is kept — Android currently imports shared 18+ packs as general.

Folder imports use the same `import_session` / `import_item` journal as Android: cancelling (or iOS ending the
background time) pauses the import, and picking the same folder again continues where it stopped. Hidden files and
iCloud placeholders (files not downloaded to the iPhone) are skipped.

Imports run on a background thread; the app asks iOS for extra background time so a short switch to another app
doesn't stop them. Copies the system made for the app (picker copies, `Documents/Inbox`) are deleted after import;
the user's own files are only read.

### Sending to WhatsApp

Exactly what WhatsApp's official iOS sample does (github.com/WhatsApp/stickers, `iOS/`: `Interoperability.swift`,
`StickerPack.swift`, `Limits.swift`):

1. Pre-flight in Kotlin (`WhatsAppPayload.problems`): pack id characters, name and publisher 1–128 characters,
   3–30 stickers, every sticker a 512 × 512 WebP within the size limit (static ≤ 100 000 bytes, animated
   ≤ 500 000 bytes, frames ≥ 8 ms, ≤ 10 s, same kind as the pack), 1–3 emojis, tray exactly 96 × 96 PNG ≤ 50 KB.
2. JSON: `identifier`, `name`, `publisher`, `tray_image` (base64 PNG), `animated_sticker_pack: true` (animated packs
   only, as in the sample), optional `ios_app_store_link` / `android_play_store_link` (build settings
   `STICKERDOCK_APP_STORE_LINK` / `STICKERDOCK_PLAY_STORE_LINK`), `stickers: [{image_data, emojis, accessibility_text}]`.
   The tray icon is rendered from the cover sticker (UIImage decodes WebP on iOS 14+).
3. `UIPasteboard.general.setItems([["net.whatsapp.third-party.sticker-pack": json]], localOnly, expires in 60 s)`,
   then `UIApplication.open("whatsapp://stickerPack")`. `canOpenURL("whatsapp://")` (needs
   `LSApplicationQueriesSchemes: whatsapp`) decides whether the single "WhatsApp" target is ready.

## What WhatsApp on iPhone can't do

- **No live updates.** WhatsApp stores its own copy of the pack. Edits in StickerDock don't reach it; send the pack
  again. Whether WhatsApp then replaces the old copy or adds another is WhatsApp's choice — check on a device.
- **No "added" state.** iOS gives no way to ask WhatsApp which packs it has, so the app never shows a pack as added.
- **No choice between WhatsApp and WhatsApp Business.** Both register `whatsapp://`; iOS decides which one opens.
  StickerDock shows one "WhatsApp" target.
- **No clone or second-account targeting.** iOS has no Dual Messenger/clone profiles, and an app can't pick an
  account inside WhatsApp.
- **One pack per send**, through the pasteboard; nothing runs in the background and there is no ContentProvider.
- **No access to WhatsApp's files.** Received stickers can't be imported from WhatsApp on iPhone; use a backup,
  `.wastickers` files or a folder copied from Android.

The shared screens still use Android wording in a few places ("updates automatically", "Refresh in WhatsApp",
the Android folder path, the keyboard card) — see "Shared-code changes" below.

## Known gaps in this pass

- **No PNG/JPEG/GIF/HEIC → WebP conversion.** iOS has no WebP encoder (WhatsApp's own sample bundles libwebp for
  this). Static WebP must already be 512 × 512 and under 100 KB; animated WebP is accepted as-is or after the shared
  `AnimatedWebpFixer` container repair (no re-encoding). Skipped files are counted with the reason. Adding libwebp
  (Swift package or cinterop) would close this.
- Keyboard extension: not started (needs an App Group so the extension can read the library).
- A folder import interrupted by the app being killed resumes only when the user picks the folder again (no
  security-scoped bookmark is stored).
- ZIP writing is STORED only and without ZIP64: at most 65 535 files and 4 GB per archive. Reading supports STORED,
  DEFLATE, data descriptors and ZIP64; encrypted archives are rejected.
- iPhone only (`TARGETED_DEVICE_FAMILY = 1`), portrait; no app icon yet; arm64 simulator only (no Intel Macs).
- Animated stickers in the grid depend on Coil's iOS decoder (not checked).

## Build

### CI — `.github/workflows/ios.yml` (macos-15; pushes/PRs touching `app/shared`, `app/iosApp` or the Gradle setup, or manual)

macOS runner minutes count 10× against the Actions quota of a private repository, so Android-only changes don't
trigger this workflow.

1. JDK 21 (Temurin), Gradle cache, newest stable Xcode on the runner (Kotlin 2.4 targets the Xcode 26 SDKs;
   macos-15 defaults to 16.4).
2. `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`.
3. `brew install xcodegen`, `xcodegen generate` in `app/iosApp`.
4. `xcodebuild build -scheme StickerDock -destination 'generic/platform=iOS Simulator' ARCHS=arm64 CODE_SIGNING_ALLOWED=NO`;
   the target's pre-build script runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode` from `app/`.
5. `./gradlew :shared:iosSimulatorArm64Test` — iosTest on the simulator (also runs when step 4 failed).

Nothing is signed, uploaded or deployed. `app/gradlew` is committed without the executable bit, so CI runs
`chmod +x` and the Xcode script calls it through `/bin/sh`.

### Type-check on Windows (no Mac)

Kotlin can compile iOS klibs on Windows (no linking, no simulator tests). This catches compile errors in
`iosMain`/`iosTest` against the real iOS platform libraries before CI (first run downloads ~400 MB of Kotlin/Native):

```powershell
. .\tools\dev-env.ps1; cd app
.\gradlew.bat :shared:compileKotlinIosArm64 :shared:compileTestKotlinIosArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

### On a Mac

Requirements: Xcode 26.x, JDK 21, the Android SDK (Gradle configures the Android modules too; the Xcode script
falls back to `~/Library/Android/sdk`), XcodeGen (`brew install xcodegen`).

```sh
cd app
./gradlew :shared:iosSimulatorArm64Test      # optional
cd iosApp && xcodegen generate && open StickerDock.xcodeproj
```

Run the `StickerDock` scheme on an iPhone simulator. For a device, set your team in `DEVELOPMENT_TEAM`
(project.yml or Xcode → Signing). `app/gradle.properties` caps the Gradle daemon at 2 GB for the 8 GB Windows PC;
Kotlin/Native links the Compose framework inside that daemon, so on a Mac (and in CI) put
`org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g` in `~/.gradle/gradle.properties` if the link runs out of memory.

## Needs a Mac and an Apple developer account

- Signing and provisioning (team id, the `app.stickerdock` bundle id), running on an iPhone.
- TestFlight / App Store: app icon (`Assets.xcassets/AppIcon`), review of `PrivacyInfo.xcprivacy` (declares
  UserDefaults, file timestamps, boot time and disk space; no tracking, no collected data), export compliance
  (`ITSAppUsesNonExemptEncryption = false`: HTTPS only), age rating and App Review rules for the 18+ lane and any
  user-generated content, the App Store link for `STICKERDOCK_APP_STORE_LINK`.
- Keyboard extension (App Group entitlement).

## Device checks before claiming anything

1. First launch (Compose UI, no Info.plist errors), dark mode, rotation locked to portrait.
2. Import an Android backup, a `.wastickers` file (Files, and "Open in" from a WhatsApp chat or Mail), a ZIP of
   WebP stickers, and a folder of WebP stickers (cancel halfway, pick it again → resumes).
3. Add a static pack and an animated 30-sticker pack (largest pasteboard payload) to WhatsApp; repeat with only
   WhatsApp Business installed and with both installed (which app opens?).
4. Send the same pack again after editing it: replaced or duplicated in WhatsApp?
5. Share a pack (AirDrop/Files) and import it on Android; export a backup to Files and restore it on Android.
6. 18+ gate, vault, Discover (if configured), diagnostics text.

## Shared-code changes recommended (not made here)

- Platform capabilities on `HostActions`/`WhatsAppState` (e.g. live updates, keyboard available, folder hint) so the
  shared UI can drop "updates automatically", "Refresh in WhatsApp" and the Android-only folder/keyboard copy on iPhone.
- A message flow on `HostActions` the shared Scaffold shows as a snackbar, instead of each platform drawing its own.
- Move the pure-Kotlin ZIP, archive import/export and WhatsApp-JSON code from `iosMain` to `commonMain`, so Android
  and iOS share one implementation and `jvmTest` covers it.
- Android `Exporters.libraryBackup` writes only stickers that are in a pack; loose stickers are missing from Android
  backups (the iPhone backup includes them). Android `ArchiveImporter` ignores a shared pack's 18+ rating.
