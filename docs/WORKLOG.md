# Work log (newest first)

Resume here after any interruption. Each entry: what changed, how it was verified, what is next.

## 2026-11-11 — send-first tap-to-send implemented in the app (owner: "Build it now")

Per `docs/flows/send-first-spec.md`:
- **Tap = Send** on every sticker surface (Discover grid + pack sheet, library grid, pack screen). Single
  stickers go through the existing FileProvider chooser with the sticker MIME `image/webp.wasticker`
  (`AndroidHost.shareUris`); Discover stickers download first via new `HostActions.shareStickerBytes`
  (cache/shared/send.webp). No false "Sent" toast. `markUsed` on send so Recents stays honest.
- **★ = Save**: Discover → rolling "Saved stickers" pack with Undo snackbar; the rolling-pack logic moved from
  `DiscoverSaver` into `Library.savedPack` (JVM-tested). Library/pack ★ = favourite.
- **Long-press = preview sheet**: full-width Send, Save, ⋯ (Add to WhatsApp tray, Add to a pack, Select
  multiple, Report). Library multi-select now starts via ⋯ → Select multiple; selection bar unchanged.
- **The 3+ rule is invisible**: `Library.prepareTray` tops 1–2-sticker packs up from favourites → recents
  (same kind/vault; a general pack never receives 18+). `AndroidHost.addToWhatsApp` auto-pads before
  validation and says "WhatsApp needs 3+ in a tray, so we added your recent ones." The "needs 3–30"
  dead-ends are gone from PackScreen, WhatsAppScreen and PackCard; thin packs show Add buttons.
- **Keyboard hero**: one-time first-run dialog pushes keyboard enable (prefs flag `keyboard_intro_shown`);
  WhatsApp tab lists the keyboard card first.
- **Tests**: 3 new `LibraryTest` cases (savedPack rolling/lanes; prepareTray favourites-then-recents;
  rating/vault/kind guards). `UiSnapshotTest` script updated for the new long-press/sheet gestures.

**Verified:** static review only on this PC — no JDK/Android SDK here, so CI app.yml
(`:shared:jvmTest :shared:verifySqlDelightMigration :androidApp:assembleDebug`) is the compile/test check
once pushed. Device acceptance (≤2 taps to send, keyboard-in-chat, animated send, both WhatsApps) pending
the owner's phone. Worker/admin/schema suites unchanged by this work (last runs: 259/106/5 green).

**Next:** push to run CI; phone acceptance; the two 2026-11-11 migrations still need applying (owner).

## 2026-11-11 — owner requests: verify review, publish-all, pause animated, sets of 8-12

**Verified (read-only agents + code):** A1 confirmed (no SDK_INT guards anywhere; `ApplicationInfoFlags` API 33
silently kills WhatsApp detection on 9–12L inside runCatching; `WEBP_LOSSY` API 30 crashes creator/import/tray on
9–10 as a NoSuchFieldError that escapes ImportWorker's `catch (Exception)`). W2 confirmed
(`discover_pack_stickers` had no rating filter; admin only warned; app saver promotes the local pack to adult
*after* downloading the leaked sticker).

**Owner decisions implemented (worker + schema + admin):**
1. Every safe sticker publishes: `quality_gate.enabled=false`, `publish_policy.min_quality=0` (defaults +
   migration `20261111000001`). Safety policy (policy.py) untouched — hard rules still reject explicit/minor/
   protected content. Reversible in admin Settings.
2. Animated recipes paused: new `recipes` setting `{motion:false, clip:false}`; `plan_recipes` returns
   `animated_paused` for GIF/video sources; analyze parks them **before** spending vision budget. Revive SQL in
   the migration header.
3. Packs are add-ready sets: series publish at **8**, cap **12** (was 3/30); daily trend packs 6–12.
4. W2 fixed: migration `20261111000002` adds `d.rating = p.rating` to `discover_pack_stickers`; admin blocks
   saving a rating change that would leave the sticker in a differently-rated pack (`ratingBreaksPacks`).
5. A1 fixed: SDK_INT 33 guard + legacy `getApplicationInfo(pkg, 0)` in WhatsAppDirectory; `LOSSY_WEBP` compat
   val (WEBP_LOSSY on 30+, legacy WEBP below) used by StickerNormalizer, TrayMaker, AnimatedReencoder.

**Verified by runs:** worker pytest **259 passed, 1 skipped** (new venv at backend/worker/.venv — none existed);
admin vitest **106/106** + `npm run build` green (Node v24 IS on this PC now); schema PGlite **5/5** incl. a new
W2 leak test. **Android compile NOT run here: no JDK/Android SDK on this machine** (D:\dev missing) — A1 fix
awaits CI app.yml; device re-check when the phone is connected.

**Not done / next:** apply the two new migrations to the live project (owner: `python -m stickerworker migrate`
with DB creds, or paste into Supabase SQL editor); send-first tap-to-send (spec ready, app work, needs device);
enable Reddit/Imgflip collection in HandNavigate (recipes already exist there); judge first real meme batch.

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

## 2026-11-05 — session 1, second half (07:00 →)
Commits since the first half, newest last (evidence in STATUS.md):
- Keyboard search with its own letter pad; keys kept above Android 16's edge-to-edge nav bar.
- Shelf management on the WhatsApp tab ("N of 10", warning, remove with undo).
- Release readiness: `qa` build type (R8 + debug hooks) run on the phone; fixed ML Kit under R8 and a keyboard
  teardown crash; `tools/release.ps1` signing script; SQLDelight v1 schema snapshot now committed.
- Discover infinite scroll / pull to refresh / retry (`FeedPager`, unit-tested).
- **Phone dropped off USB at 07:59** (Windows no longer sees it). Everything after that is verified with JVM tests and
  headless Compose Desktop snapshots (`UiSnapshotTest`) instead of the phone.
- Library sort (newest / most used / oldest / A–Z) and a duplicate finder with merge + undo.
- Share stickers; mark 18+; move to / out of the private vault (single and bulk).
- Worker quality gate (helper agent; re-run here: pytest 198/198) and the PostgREST 1,000-row paging fix.
- Auto tags: on-device text recognition (Latin + Devanagari), image labels, emoji words → `auto_tags`; WhatsApp
  file names no longer shown as names. Needed because 791 of 793 stickers on the phone had no words.

**When the phone is back:** install the dev build, `--es cmd autotag --ei n 30` (logs what OCR read), open
Settings → Words on stickers, search "kya" / "gussa" in the library and the keyboard, open Find duplicates,
scroll Discover past 60, then the owner checklist in OWNER_REQUESTS.md.
