# Backlog (priority order; core first)

## Core — WhatsApp everywhere
1. Owner-present device checks: WhatsApp Add dialog (user 0), Dual Messenger via relay chooser, P2 refresh timing,
   P3 >10 packs, P4 in-app second account, K2–K4 keyboard inside WhatsApp (capture MIME list; no messages sent).
2. Relay chooser: if One UI shows a chooser for the forwarded intent, label the companion clearly; consider a
   companion launcher shortcut as the fallback path.
3. Companion first-run guide in the main app (copyable adb commands exist; add a Windows one-click script
   `tools/setup-dual.ps1` that installs + grants for the detected clone user).
4. ~~"Add to both" one-flow~~ — DONE: "Add to all N WhatsApps" queues main → Business → Dual after each confirmation.
5. ~~Shelf management UI~~ — DONE: WhatsApp tab lists packs offered to WhatsApp ("N of 10", warning past 10, remove
   with undo); pack screen warns before adding an 11th. Lift the warning if P3 proves WhatsApp reads more.
6. Keyboard: ~~in-keyboard search~~ (DONE, own letter pad); ~~pack tabs with icons, long-press preview, haptics~~
   (DONE in the shared `KeyboardPanel`, snapshot-tested; phone check pending).
6b. ~~Searchable imports~~ — DONE: on-device auto tags (ML Kit text Latin + Devanagari, image labels, emoji words,
   romanized Hindi) in `auto_tags`; file names hidden as names. Phone check pending (`--es cmd autotag`).

## Core — library & import
7. ~~Recover rejected animated stickers; GIF → animated~~ — DONE (Fresco re-encoder, Lottie `.was`; 5 of 1,571 left).
8. ~~Video → animated sticker~~ — DONE (`CreateVideoStickerActivity`, start/length chips, caption).
8b. Smoother converted animations: long Lottie `.was` stickers come out at ~5 fps (30 frames × 198 ms) to fit
   500 KB. Encode frames 2+ as changed-rectangle ANMF deltas (blend off, even offsets) so the same budget holds
   2–3× more frames; keep full frames when most pixels change (e.g. background colour swaps).
9. ~~Find & merge duplicates already in the library~~ — DONE: Settings → Find duplicates (hashes older stickers
   first); keeper takes the copies' pack places, covers, favourite and words; undo; warns when a pack drops below 3.
10. Import progress UI for the companion path; SAF picker flow check on device (A2 UI, A3 process death).
11. ~~Pagination for large libraries~~ — DONE (grid grows 300 at a time as you scroll).

## Core — AI factory & Discover
12. Supabase project (owner) → apply migration, deploy `intake`, connect worker cron, publish first stickers.
13. Discover: trending + categories + search wired to the live project; follow packs; report flow end to end.
14. ~~Quality gate before auto-publish~~ — DONE in the worker: local 0–100 score (blank, tiny subject, blur, caption
    contrast/clipping, near-duplicate render, animation timing); below 60 → `held` for admin review; optional
    vision judge (off by default, counts against the daily vision budget). Still open: caption A/B variants,
    Admin "Held" tab with scores + bulk approve, re-release held stickers when the threshold changes.
15. Original art via Cloudflare Workers AI FLUX (owner account) for categories without good sources.

## HandNavigate → v2 intake — DONE on HandNavigate branch `stickerdock-v2-intake` (01ea9e1, not pushed)
`service/stickerdock-v2.cjs` + `createSink()`; 41/41 service tests pass. Merge it and set the two env vars.
Original plan below for reference.
- HandNavigate (`../hand`, `service/stickerdock.cjs`) posts to v1 `/api/ingest` with a claim/report job queue that
  v2 doesn't have. Add a v2 sink: `POST {SUPABASE_URL}/functions/v1/intake` with `x-intake-token` and
  `{image_base64, mime, site, source_url, page_url, author, license, topic, collector:"handnavigate"}` →
  `{id, duplicate}`; HandNavigate keeps its own daily plans. Config: `STICKERDOCK_V2_INTAKE_URL`,
  `STICKERDOCK_V2_INTAKE_TOKEN`. Fallback today: `python -m stickerworker intake DIR --collector handnavigate`.

## From v1 worth porting later
- Photo sticker editor (crop, outline caption, ML Kit subject cutout, erase/restore brush) — v1 `StickerArtworkEditor`.
- Editable backups with project history — v1 `EditableBackup`.
- Public pack codes / deep links (`stickerdock://pack/<id>`) for sharing Discover packs.
- Cloud backup of the library across phones (account-scoped, vault excluded).
- iPhone: keyboard extension (copy-to-paste), signing/TestFlight (needs Apple account).
