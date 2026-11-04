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
- Pending owner-present checks: WhatsApp add dialogs (P1/D4), keyboard in WhatsApp (K2–K4), relay chooser.

## 2026-11-05 — tests
- `:shared:jvmTest` — 21/21 pass (12 core incl. AnimatedWebpFixer canvas/keyframe-drop/timing; 9 library incl.
  import grouping). Earlier: 17/17 pass (CoreTest 9: SHA-256 vs JDK on 8 sizes, WebP simple/lossless/extended/animated
  parsing, size/kind/timing rules, truncation, WhatsApp EXIF pack metadata, animated mux, tags/emoji;
  LibraryTest 8: dedup + restore of soft-deleted, invalid input writes no file, pack kind/vault/30 cap and tray,
  adult rating propagation, shelf eligibility 3–30 + version bumps + undo, autoPack splitting, search, purge).
- Device facts (S24 Ultra): see WORKLOG 2026-11-05.
