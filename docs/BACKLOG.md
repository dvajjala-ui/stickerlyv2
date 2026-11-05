# Backlog (priority order; core first)

## Core — WhatsApp everywhere
1. Owner-present device checks: WhatsApp Add dialog (user 0), Dual Messenger via relay chooser, P2 refresh timing,
   P3 >10 packs, P4 in-app second account, K2–K4 keyboard inside WhatsApp (capture MIME list; no messages sent).
2. Relay chooser: if One UI shows a chooser for the forwarded intent, label the companion clearly; consider a
   companion launcher shortcut as the fallback path.
3. Companion first-run guide in the main app (copyable adb commands exist; add a Windows one-click script
   `tools/setup-dual.ps1` that installs + grants for the detected clone user).
4. "Add to both" one-flow: queue main + Business + Dual adds back-to-back after each WhatsApp confirmation.
5. Shelf management UI: show shelf size, warn above 10 packs until P3 proves WhatsApp accepts more.
6. Keyboard: in-keyboard search box (mini key row), pack tabs with icons, long-press preview, haptics.

## Core — library & import
7. Recover the last rejected animated stickers once `AnimatedReencoder` (Fresco) is verified on device; GIF → animated.
8. Video → animated sticker (MediaMetadataRetriever frames → same encoder/muxer), 0.5–3 s trim UI.
9. Find & merge duplicates already in the library (stickers imported before dHash existed).
10. Import progress UI for the companion path; SAF picker flow check on device (A2 UI, A3 process death).
11. Pagination for libraries > 2,000 stickers (current grid loads up to 600 per query).

## Core — AI factory & Discover
12. Supabase project (owner) → apply migration, deploy `intake`, connect worker cron, publish first stickers.
13. Discover: trending + categories + search wired to the live project; follow packs; report flow end to end.
14. Quality judge pass on generated stickers (vision model score) before auto-publish; caption A/B variants.
15. Original art via Cloudflare Workers AI FLUX (owner account) for categories without good sources.

## From v1 worth porting later
- Photo sticker editor (crop, outline caption, ML Kit subject cutout, erase/restore brush) — v1 `StickerArtworkEditor`.
- Editable backups with project history — v1 `EditableBackup`.
- Public pack codes / deep links (`stickerdock://pack/<id>`) for sharing Discover packs.
- Cloud backup of the library across phones (account-scoped, vault excluded).
- iPhone: keyboard extension (copy-to-paste), signing/TestFlight (needs Apple account).
