# Work log (newest first)

Resume here after any interruption. Each entry: what changed, how it was verified, what is next.

## 2026-11-05 — session 1 (owner asleep; autonomous overnight run)

**Device (owner's Galaxy S24 Ultra)** — authorized over USB.
- SM-S928B, Android 16 (SDK 36), One UI 8.5 (`ro.build.version.oneui=80500`).
- Users: 0 owner · **95 `DUAL_APP` type `profile.CLONE` (parent 0)** · 150 Secure Folder (`profile.PRIVATE`).
- `com.whatsapp` 2.26.39.74 installed in user 0 **and** user 95. WhatsApp Business not installed.
- Default IME Samsung Keyboard (`com.samsung.android.honeyboard`). Sticker.ly installed in user 0.
- Changed for overnight testing: `stay_on_while_plugged_in` 0 → 2 (USB). **Restore:** `adb shell svc power stayon false`.
- Rule: never open WhatsApp chats or send anything; install/uninstall our builds and read state only.

**Toolchain installed** (D:\dev): Temurin JDK 21.0.13+9 (sha256 verified), Android cmdline-tools +
platform-tools (adb 1.0.41), Gradle 9.6.1 (sha256 verified). Gradle user home `D:\dev\gradle-home`.

**Decisions made by owner** — see AGENTS.md "Owner decisions".

**Done tonight (see STATUS.md for evidence)**
- Android app builds (AGP 9.1.1 / Kotlin 2.4.10 / CMP 1.11.1); 24 JVM tests; installed on the S24 as
  `app.stickerdock.dev` in user 0 **and** user 95 (Dual Messenger companion, cross-user grants done).
- Real data: both WhatsApps' sticker folders imported into one library (672 unique after dedup).
- Provider validated (official sample rules) in user 0 and via the companion proxy in user 95.
- Owner's phone has an unlock pattern → screen-dependent checks via debug `ShowcaseActivity` (show-when-locked,
  our own UI only). Debug hooks: `adb shell am broadcast --user N -f 0x20 -a app.stickerdock.debug.COMMAND
  -p app.stickerdock.dev --es cmd <probe|import|companion_import|shelf_some|provider_selftest|stats|wipe>`.
- Test data streamed into each profile's app storage with `run-as app.stickerdock.dev --user N` (files at
  `files/import/wa` and `files/import/dual`); adb-created files in app media dirs are NOT readable (EACCES).
- Agents running: backend worker (Python), admin panel (React) + intake function, iOS shell.

**Next**
1. Fresco-based `AnimatedReencoder` build → re-import to recover remaining animated rejects; GIF → animated.
2. K1 keyboard check (`tools/keyboard-check.sh`), restore Samsung keyboard afterwards.
3. Integrate agents' work; local Discover dev server so the phone shows worker dry-run stickers.
4. Owner-present: WhatsApp Add dialogs (user 0, Dual via relay chooser), keyboard in WhatsApp (MIME list).
