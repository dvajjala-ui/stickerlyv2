# Status — what is verified (newest first)

Only claims backed by a test run, build or device check appear here.

## 2026-11-05 — on the owner's Galaxy S24 Ultra (Android 16 / One UI 8.5), debug build, phone locked (pattern)
All checks below ran over adb with debug-only command hooks; no WhatsApp UI was touched.
- **Dual Messenger install (D1)** ✅ `pm install-existing --user 95 app.stickerdock.dev` → "installed for user: 95".
- **Cross-user grant (D2)** ✅ `pm grant [--user 95] … INTERACT_ACROSS_USERS` → granted in users 0 and 95.
- **Companion ↔ main library (D3)** ✅ companion in user 95: hub ping true, shelf and library readable over
  `content://0@…sync`; main copy reaches the clone's WhatsApp whitelist provider (`content://95@com.whatsapp…`).
- **CrossProfileApps** ❌ from user 0 lists no targets (clone not offered); companion → user 0 works. Workaround in
  place: forwarded share intent → `CompanionRelayActivity`; a system chooser appears (ResolverActivity) — needs a
  screen check.
- **Real-data import (A2, debug path)** ✅ 666 files from WhatsApp/Media/WhatsApp Stickers (user 0) in 6.2 s →
  545 added, 40 duplicates, 81 skipped; companion sent 899 files from user 95 → 165 added, 611 duplicates (shared
  with account 1), 123 skipped. One library: **710 unique stickers (375 static, 335 animated), 51 packs.**
  Skips are animated stickers WhatsApp accepted in chats but packs reject (canvas > 512 or delta-frame > 500 KB);
  `AnimatedWebpFixer` recovered 82 by container rewrite; the rest need native re-encoding (backlog).
- **Provider correctness (P1/D5 data path)** ✅ 4 shelf packs (2 static, 2 animated, 30 each) pass the official
  sample's validation rules read through our provider in user 0 **and** through the companion's proxy provider in
  user 95 (what the clone WhatsApp will read).
- **Near-duplicates**: dHash on import (blank first frames skipped; animated also needs same frame count and ±10%
  duration) → 710 → 672 unique from both accounts. DB v1→v2 migration on the phone kept all 710 rows first.
- **Animated repair**: budgeted Fresco re-encoder → main-account import 666 files in 55 s: 549 added, 71 dup,
  46 skipped (was 81). Remaining skips: 26 WhatsApp `.was` Lottie stickers (converter added since, pending
  re-run), 17 with sub-8 ms frames the decoder rejected, 3 oversize.
- **Discover end to end (X1 partial)** ✅ worker offline run (FakeAI, 15 sample images: explicit, protected
  person, blank, tiny and duplicate correctly rejected; baby kept general) → `tools/feed_from_local.py` →
  `tools/dev_discover.py` → phone over `adb reverse` → Discover tab shows packs ("Office Ke Dukh") and stickers.
- UI reviewed on device (Library, Packs, Pack, WhatsApp hub, Discover); fixes applied: segmented switch,
  4-column grid, cover badge, expanded pack sheet with visible Save button, dialog sizing, infinite scroll.
- Pending owner-present checks: WhatsApp add dialogs (P1/D4), keyboard in WhatsApp (K2–K4), relay chooser.

- **Full two-account import, all converters** (Lottie `.was`, Fresco re-encoder, container fix, dHash): main
  666 files → 573 added / 73 dup / 20 skipped in 74 s; Dual Messenger 905 files → 187 added / 685 dup / 33 skipped
  in 146 s. **One library of 760 unique stickers (357 static, 403 animated)**; 8 shelf packs pass validation.
  Remaining skips were single-frame "animations" (static stickers in an animation wrapper) → now treated as
  static (fix pending re-run) and 3 files over 900 KB.
- **Final migration numbers** (single-frame fix in): main 666 → 590 added / 73 dup / **3 skipped**; Dual 905 →
  201 added / 702 dup / **2 skipped** (all five are animations over 900 KB). **791 unique stickers** (388 static,
  403 animated) from 1,571 files; 16/16 provider self-tests pass (8 packs × user 0 and user 95 proxy).
- **Photo creator** ✅ on device: ML Kit module downloaded, background removed, white outline, outlined caption
  "Kya baat hai boss", saved into a new "My creations" pack.
- **Keyboard (K1)** ✅ StickerDock keyboard shows the library (animated stickers play); tapping a sticker committed
  it as `image/webp.wasticker` (26 KB) into the test field, which decoded it. Owner's Samsung Keyboard restored.
- **Keyboard across Dual Messenger (K3)** ✅ a text field running in the clone profile (user 95) was served by the
  **main profile's** StickerDock keyboard (One UI 8.5 shares the parent's IME with the clone, unlike AOSP's
  per-profile IMEs); the sticker arrived as `content://0@app.stickerdock.dev.files/stickers/…`
  (`image/webp.wasticker`, 26 KB) and decoded. So the keyboard reaches the Dual Messenger WhatsApp with no
  companion or adb setup. Both profiles' default keyboard restored to Samsung Keyboard.
- **Keyboard search** ✅ 🔍 tab → own letter pad (works where the host field has no text); typing "chalo" showed
  "Chalo chalo", tapping it committed `image/webp.wasticker` (161 KB, decoded). Android 16 draws the IME edge to
  edge, so the keys now sit above the navigation bar (`navigationBarsPadding`), checked on screen.
- **Shelf management** ✅ WhatsApp tab lists the packs offered to WhatsApp with covers and per-WhatsApp add buttons;
  with 12 offered it shows "12 of 10" and the guideline warning; ✕ removed "Animated 4" with an Undo snackbar.
  Shelf restored to the original 8 afterwards. `:shared:jvmTest` 24/24 (shelf flow keeps under-3 packs listed).
- **Samsung Freecess** froze the companion (user 95) ~50 s into a background import; the debug broadcast then hit
  the background ANR timeout and Android killed it. Real imports run from foreground screens / foreground jobs;
  debug hooks now release the broadcast immediately and run with the showcase screen in front.

## 2026-11-05 — tests (re-run by the lead, not only by the agents)
- Admin: `npm test` 76/76 pass; `npm run build` ok.
- Worker: `pytest` 158/158 pass (108 s). Ruff not installed in that venv (agent reported clean).
- `:shared:jvmTest` — 21/21 pass (12 core incl. AnimatedWebpFixer canvas/keyframe-drop/timing; 9 library incl.
  import grouping). Earlier: 17/17 pass (CoreTest 9: SHA-256 vs JDK on 8 sizes, WebP simple/lossless/extended/animated
  parsing, size/kind/timing rules, truncation, WhatsApp EXIF pack metadata, animated mux, tags/emoji;
  LibraryTest 8: dedup + restore of soft-deleted, invalid input writes no file, pack kind/vault/30 cap and tray,
  adult rating propagation, shelf eligibility 3–30 + version bumps + undo, autoPack splitting, search, purge).
- Device facts (S24 Ultra): see WORKLOG 2026-11-05.
