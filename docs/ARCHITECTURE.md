# Architecture

```
 collectors ──► intake (Edge Function) ──► Supabase: source_images ──► worker (GitHub Actions, Python)
 (HandNavigate,                               ▲  private bucket         prescreen → analyze (Groq) → policy
  admin upload)                               │  "sources"              → create (recipes) → compose packs
                                              │                         → trends
 Admin web (React) ── RLS as admin ───────────┤
                                              ▼
                         stickers / packs / pack_items (public bucket "stickers")
                                              │  discover_* views + RPCs (anon, published only)
                                              ▼
 Phone app (Kotlin Multiplatform + Compose) ── local SQLite library ── WhatsApp provider / keyboard / companion
```

## Mobile (`app/`)
- `shared/` (KMP: Android, iOS, JVM tests) — `core/` WebP parsing (simple + extended + animated), animated muxer,
  WhatsApp rules, SHA-256, tags/emoji; `library/` SQLDelight sticker-first DB + rules; `discover/` client and saver;
  `ui/` Compose Multiplatform screens; `host/` interface the platform implements.
- `androidApp/` — `whatsapp/` provider (WhatsApp contract) and directory (targets, whitelist, intents); `dual/`
  companion mode, sync provider, cross-profile launch; `keyboard/` IME + test field; `imports/` SAF folder importer,
  archives, share-sheet, WorkManager job; `export/` .wastickers and backup; `media/` normalizer and tray maker.
- Versions follow JetBrains' KMP-App-Template (AGP 9.1, Kotlin 2.4.10, CMP 1.11.1, Gradle 9.6.1).

### WhatsApp delivery model
- **Shelf**: packs flagged `on_shelf` with 3–30 live stickers are what our provider lists. Adding a pack puts it on
  the shelf. Each change bumps `data_version` (= `image_data_version`) and notifies observers.
- **Companion** (any other Android user/profile): same APK installed with `pm install-existing --user N`, both copies
  granted `INTERACT_ACROSS_USERS` (development permission, adb-grantable). The companion's provider proxies to the
  main copy's `SyncProvider` at `content://0@<app>.sync/…` (signature permission), so the clone WhatsApp sees the one
  library. Requests to add a pack in the clone are queued in the main copy and executed by the companion, which the
  main copy opens with `CrossProfileApps.startMainActivity`.
- **Keyboard**: AOSP binds keyboards per profile (Android 10+), but on One UI 8.5 the Dual Messenger clone uses the
  main profile's keyboard (verified: commit from user 0 decoded in a user-95 field via `content://0@…`). So the main
  copy's keyboard already covers Dual Messenger; the companion keyboard is the fallback for OEMs that bind per profile.
- Verified on One UI 8.5 (S24 Ultra): `CrossProfileApps.targetUserProfiles` is empty from user 0 towards the clone
  (it works from the clone towards user 0). The main copy therefore detects companions with a cross-user
  `call("whoami")` on `content://<user>@<app>.sync` and opens them with an `ACTION_SEND` of type
  `application/vnd.stickerdock.request`, which Android forwards parent → clone; `CompanionRelayActivity` is disabled in
  the main copy so the intent can only land in the companion (a system chooser may appear first).
- The companion can also send its profile's WhatsApp sticker folder to the main library (`incoming` insert +
  `begin_import`/`finish_import` calls), so both accounts' stickers end up in one deduplicated library.

### Import pipeline
`DocumentTree` (one DocumentsContract query per folder) → `StickerNormalizer` (valid WhatsApp WebP kept
byte-exact; static images re-encoded to 512² ≤100 KB; animated: container repair `AnimatedWebpFixer` for nearly-512
canvases, otherwise `AnimatedReencoder` = Fresco decode + composite + scale + Android WebP encode + shared muxer;
GIFs → animated) → `Library.addSticker` (SHA-256 exact dedup + dHash near-duplicates; animated near-dups must also
match frame count and duration) → `packImported` (original pack names with ≥4 stickers become packs; the rest merge
into "From WhatsApp" packs of 30, static/animated apart).

### Words for stickers that arrive without any (auto tags)
Received WhatsApp stickers are named like `STK-20241027-WA0008` and carry at most three emojis. (On the owner's
phone, 791 of 793 had no words at all, so search found nothing.) `AutoTagWorker` runs after imports and at app start.
It reads each sticker on the device with ML Kit, whose models come from Google Play services; nothing is uploaded:
- Latin and Devanagari text recognition, on a mid-grey background so white captions with black outlines both read;
- image labels.

`core/AutoTags` turns what was read into search words:
- the caption text;
- a rough Devanagari→Latin romanization, so typing "kya baat" finds "क्या बात";
- Hinglish words for labels ("Smile" → khush) and for WhatsApp's emojis (😡 → gussa, angry).

The words go to `sticker.auto_tags` (migration `2.sqm`), separate from the user's tags. The caption becomes the
name only when the sticker has none, or only a file name. File names never show as names
(`AutoTags.isMachineName`), including in WhatsApp's accessibility text and the companion keyboard.

## Backend (`backend/`)
- `supabase/migrations` — schema, RLS, views, RPCs (see the SQL header for the data flow).
- `supabase/functions/intake` — collector endpoint (token, magic bytes, dedup, takedown check).
- `worker/` — Python pipeline; `LocalStore` + fake AI for offline dry runs; budgets enforced from `settings`.
- Storage budget: Supabase free = 1 GB files / 5 GB egress. Originals are pruned after use; stickers are ~30–80 KB.
  Move public stickers to Cloudflare R2 (free egress) when egress grows.

## Admin (`admin/`)
Static React SPA; Supabase Auth magic link; all writes through RLS (`is_admin()`), takedowns through `take_down()`.
