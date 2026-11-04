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
- **Keyboard**: per-profile on Android 10+, so the companion also provides the keyboard inside the clone.

## Backend (`backend/`)
- `supabase/migrations` — schema, RLS, views, RPCs (see the SQL header for the data flow).
- `supabase/functions/intake` — collector endpoint (token, magic bytes, dedup, takedown check).
- `worker/` — Python pipeline; `LocalStore` + fake AI for offline dry runs; budgets enforced from `settings`.
- Storage budget: Supabase free = 1 GB files / 5 GB egress. Originals are pruned after use; stickers are ~30–80 KB.
  Move public stickers to Cloudflare R2 (free egress) when egress grows.

## Admin (`admin/`)
Static React SPA; Supabase Auth magic link; all writes through RLS (`is_admin()`), takedowns through `take_down()`.
