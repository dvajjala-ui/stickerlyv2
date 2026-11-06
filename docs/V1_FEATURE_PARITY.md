# StickerDock v1 → v2 feature parity

What v1 (`D:\5. GitUploaded\sticker`) could do, and whether v2 (`D:\5. GitUploaded\stickerdock-next`) has it.
Built from the **code** of both repos (7 Nov 2026), not the marketing docs. v1 docs describe many plans that never
shipped; those are marked "planned in v1, never built", not as features.

**Status key:** ✅ Have · 🟡 Partial (note says what's missing) · ❌ Missing · ♻️ Replaced (v2 does it differently).

## Summary
v2 is a clean rewrite (Kotlin Multiplatform + Compose, one shared codebase for Android + iOS) with a real AI content
backend (Supabase + Python worker + Groq). It **keeps the whole core sticker product** — library, packs, import,
WhatsApp, keyboard, backup, Discover, 18+/vault — and **adds** the automated content factory v1 only ever planned.
What it drops is the heavy **in-app art studio** (freehand brush, manual masks, multi-layer projects with undo) and a
few web/account pieces (personal cloud account/login, the separate Creator Studio web page, Sticker.ly link import).

| Status | Count | |
| --- | --- | --- |
| ✅ Have | ~41 | core product carried over and improved |
| ♻️ Replaced | ~9 | done a different (usually simpler/server-side) way |
| 🟡 Partial | ~10 | present but missing sub-features |
| ❌ Missing | ~13 | mostly the art studio and web-account pieces |

---

## Library and packs
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Sticker library with thumbnails | `app/.../StickerStore.kt`, `StickerDockScreens.kt` | ✅ | `ui/LibraryScreen.kt`, `library/Library.kt`, SQLDelight store |
| Packs (create, rename, add/remove) | `StickerPackMetadata.kt`, `PackPresentation.kt` | ✅ | `ui/PackScreen.kt`, `ui/LibraryScreen.kt` |
| Pack "shelf" organisation | — | ✅ | `ui/PackScreen.kt`, `host/HostActions.kt` (shelves) |
| Favorites | `StickerFavorites.kt` | ✅ | favorites filter in `ui/LibraryScreen.kt` (`★ Favorites`) |
| Reorder stickers in a pack | `StickerStore.kt` (positions) | ✅ | positions in `library/Library.kt` |
| Per-sticker name / tags / emoji edit | `StickerMetadata.kt`, `StickerEmojiTags.kt` | ✅ | `ui/LibraryScreen.kt` `updateMeta()` (Name, Tags, up-to-3 Emojis) |
| 10,000-sticker performance | `LargeLibrarySearchTest.kt`, `StickerPreviewCache.kt` | 🟡 | large-library works; a dedicated thumbnail cache like v1's isn't a separate module yet (backlog) |
| Storage budget / usage view | `StorageBudget.kt` | ❌ | no in-app storage meter yet |
| Duplicate finder (dHash) | `Dhash` logic | ✅ | `core/Dhash.kt`, `ui/DuplicatesScreen.kt`, Settings → Find duplicates |

## Import / export
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Import from phone folder (SAF) | `FolderStickerImporter.kt` | ✅ | `android/imports/DocumentTree.kt`, `Importers.kt`, `ImportWorker.kt` |
| Resumable / checkpointed folder scan | `FolderStickerImporterCheckpointTest.kt` | 🟡 | background `ImportWorker` exists; explicit resume-after-process-death still to verify on device |
| Import pack ZIP | `PackZipImporter.kt` | ✅ | `android/imports/Importers.kt`, `ios/zip/Zip.kt` |
| Multi-ZIP batch import | `CatalogImports.kt` | ✅ | `Importers.kt` handles multiple archives |
| Photo→pack bulk importer | `PhotoPackImporter.kt`, `PhotoPackSheet.kt` | ♻️ | one-at-a-time create (`CreateStickerActivity`) + folder import; no dedicated "photos→pack" wizard |
| EXIF sticker-pack metadata read | `StickerPackMetadata.kt` | ✅ | `core/Webp.kt`, `android/media/StickerMedia.kt` read pack EXIF |
| Receive a shared image to make a sticker | `IncomingMedia.kt`, `IncomingStickers.kt` | 🟡 | share-in path partly in `android/MainActivity.kt`; needs device check |
| ADB export ZIP batcher | `tools/adb_sticker_export.py` | ♻️ | `tools/release.ps1` + backup ZIP; no separate adb batcher |
| Import from Sticker.ly links | `StickerlyLinks.kt` | ❌ | not ported (owner's own Sticker.ly export ingestion is a backlog item, not scraping) |

## WhatsApp
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| WhatsApp sticker ContentProvider | `WhatsAppStickerProvider.kt`, contract test | ✅ | `android/whatsapp/WhatsAppStickerProvider.kt` + contract validator |
| Add pack to WhatsApp (consumer + Business) | `WhatsAppIntegration.kt` | ✅ | `ui/WhatsAppScreen.kt`, `android/whatsapp/*`; both targets |
| Pack size / tray / 512px validation | `WhatsAppIntegration.kt` | ✅ | `android/whatsapp/WhatsAppValidator.kt`, `core/StickerRules.kt` |
| Dual Messenger / cloned WhatsApp support | — (planned) | ✅ | `android/dual/*` (CompanionImporter, SyncProvider, Dual) |
| Two independent accounts inside one WhatsApp | planned in v1, never built | ❌ | not possible via the official API; both repos correctly avoid claiming it |

## Search / organise
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Text search over names/tags | `StickerMetadataSearchTest.kt` | ✅ | `library/Library.kt`, `ui/LibraryScreen.kt` |
| Filter by kind (static/animated) | library filters | ✅ | `KindFilter` in `ui/LibraryScreen.kt` |
| Sort (recent, name, …) | library sort | ✅ | sort menu in `ui/LibraryScreen.kt` |
| Saved search history | `StickerSearchHistory.kt` | ❌ | not ported |
| Auto-tagging (on-device OCR + labels) | OCR caption gate (`tools/...`) | ✅✨ | `android/tags/AutoTagger.kt`, `core/AutoTags.kt` — on-device ML Kit, reads words on every sticker |
| Per-sticker emoji for WhatsApp search | `StickerEmojiTags.kt` | ✅ | emoji field in `updateMeta()` |

## Creator / editor
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Photo → sticker | `StickerArtwork.kt`, `ClipStickerCreator.kt` | ✅ | `android/create/CreateStickerActivity.kt` |
| Automatic background cutout | `AutomaticCutout.kt`, `ArtworkCutout.kt` | ✅ | `android/create/Cutout.kt` (ML Kit subject segmentation) |
| Caption (outlined, top/bottom, scale, place) | `ClipStickerEditor.kt` | ✅ | `CreateStickerActivity.kt` (caption, top/bottom chip, scale slider, pinch/drag) |
| White outline / sticker border | `StickerArtwork.kt` | ✅ | `android/create/StickerComposer.kt` |
| Freehand brush / paint | `ArtworkBrush.kt` | ❌ | no in-app paint tool |
| Manual mask / eraser editor | `ArtworkMasks.kt`, `StickerMaskEditor.kt`, `StickerCutoutEditor.kt` | ❌ | only automatic cutout; no manual touch-up |
| Multi-layer artwork projects | `ArtworkProjects.kt` | ❌ | no project/layer system |
| Undo/redo history | `ArtworkHistory.kt` | ❌ | not in the simplified editor |
| Video / GIF → animated sticker | `VideoClipFrames.kt`, `WebpAnimation.kt` | ✅ | `android/create/CreateVideoStickerActivity.kt`, `android/media/AnimatedReencoder.kt`, `core/Webp.kt` |
| Animated WebP mux/fix | `AnimatedWebpMuxer.kt`, `AnimatedWebpFixer.kt` | ✅ | `core/AnimatedWebpMuxer.kt`, `core/AnimatedWebpFixer.kt` |

## Keyboard
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Sticker keyboard (IME) | planned/partial in v1 | ✅ | `android/keyboard/StickerKeyboardService.kt`, `KeyboardData.kt` |
| Search stickers from the keyboard | — | ✅ | search in `StickerKeyboardService.kt`; `ui/KeyboardPanel.kt` |
| Send sticker into any chat | — | ✅ | keyboard commit / share from `StickerKeyboardService.kt` |

## Discover / catalog (public content)
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Public pack catalog in app | `CatalogScreens.kt`, `CatalogDiscovery.kt` | ✅ | `ui/DiscoverScreen.kt`, `discover/DiscoverClient.kt`, `FeedPager.kt` |
| Paged Discover feed | `CatalogDiscovery.kt` | ✅ | `discover/FeedPager.kt` |
| Pack preview before adding | `CatalogPreview.kt`, `CatalogStickerPreview.kt` | ✅ | `ui/PackScreen.kt` preview |
| Download a Discover pack into library | `CatalogImports.kt` | ✅ | `ui/DiscoverScreen.kt` → add to library |
| Content rating labels (general/18+) | content labels | ✅ | 18+ lane in Discover + Settings toggle |
| Community (user-uploaded) catalog | `CommunityCatalog.kt` (planned) | ❌ | deferred: needs moderation/identity/age/scanning (out of MVP, by design) |
| Curated read-only public gallery | curated gallery | ♻️ | Discover itself is the curated feed; no separate gallery screen |

## Sharing
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Share a sticker (FileProvider) | `StickerFiles.kt` | ✅ | `android/MainActivity.kt`, `host/HostActions.kt` |
| Share/export a whole pack | `PackSharing.kt` | ✅ | `ui/PackScreen.kt`, `android/export/Exporters.kt` |
| Public pack share links | `PublicPackLinks.kt` | ♻️ | Discover packs are shareable server-side; no per-pack public URL minting in-app |

## Backup / restore
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Editable backup (whole library → ZIP) | `EditableBackup.kt`, `StickerBackup.kt` | ✅ | Settings → Export/Restore; `android/export/Exporters.kt`, `android/imports/Importers.kt` |
| Backup round-trip fidelity | `StickerBackupRoundTripTest.kt` | ✅ | names, tags, packs preserved; vault excluded |
| Private cloud library (account sync) | `CloudLibrary.kt`, `CloudSyncPolicy.kt`, `api/cloud.py` | ❌ | no personal cloud account/sync (Discover is the only cloud); local backup only |

## Privacy / 18+
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| 18+ content toggle | content labels | ✅ | Settings "18+ stickers" |
| Private vault (never synced/shared) | — | ✅✨ | Settings "Private vault" with confirm dialog |
| Report content | `api/admin.py` reports | ♻️ | server-side `reports` table + admin; in-app report button is a backlog item |
| Takedown flow | takedown logic | ✅ | `backend/supabase` takedowns + admin Reports page |

## Admin (web)
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Admin panel | `admin/*.js` (vanilla JS) | ♻️✨ | full React SPA (`admin/src/pages/*`), RLS-enforced |
| Sources library (review collected images) | `admin/sources.js`, `api/_sources.py` | ✅ | `admin/src/pages/Sources.tsx` |
| Upload images from your PC | `admin/sources.js` | ✅ | Sources page upload panel (SHA-256 dedup, takedown check) |
| Stickers & packs management | `admin/app.js` | ✅ | `pages/Stickers.tsx`, `pages/Packs.tsx`, `PackEditor.tsx` |
| Reports / takedowns | `api/admin.py` | ✅ | `pages/Reports.tsx` |
| Trends control | `tools/trend_intelligence.py` | ✅ | `pages/Trends.tsx` + worker `trends` stage |
| Collection control (topics, daily queue) | `admin/collect.js`, `api/_collection_control.py` | 🟡 | topics live in HandNavigate DB; a v2 admin "Collect" page is the parked `handnavigate-queue` design |
| Settings / budgets / protected people | `api/_admin.py` | ✅ | `pages/Settings.tsx`, `protectedPeople.ts` |
| Worker runs / AI usage view | `tools/...` | ✅ | `api/workerRuns.ts`, `api/aiCalls.ts` |
| Open (no-login) admin | `docs/OPEN_ADMIN.md` | ♻️ | v2 uses magic-link + `admins` RLS (safer for a public repo); can be relaxed |
| Offline demo mode | — | ✅✨ | admin runs on fixtures with no backend |

## Backend / API
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Content database | `supabase/migrations/*` (12) | ♻️ | `backend/supabase/migrations/*` (3, cleaner schema) + migrate ledger |
| Image intake endpoint | `api/ingest.py`, `api/upload.py` | ✅ | `backend/supabase/functions/intake` Edge Function |
| Captions API | `api/captions.py`, `tools/personal_ai_worker.py` | ♻️ | moved into the worker (`pipeline/captions.py`, Groq) |
| Personal AI | `api/personal_ai.py` | ♻️ | the worker's analyze/create pipeline |
| Account / auth / email | `api/account.py`, `api/auth_email.py`, `site/account.*` | ❌ | no user accounts in v2 (admin magic-link only) |
| Pinterest OAuth connect | `api/pinterest_callback.py`, `tools/pinterest_connection.py` | ♻️ | HandNavigate owns signed-in Pinterest/Reddit; no OAuth in v2 |

## Automation / collection (the "factory")
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Image collection (Pinterest/Reddit) | `tools/pinterest_worker.py`, HandNavigate | ✅✨ | HandNavigate on the Oracle VM, signed-in, daily → Supabase |
| Flickr / Openverse / Imgflip adapters | `tools/reaction_media.py`, etc. | 🟡 | HandNavigate has recipes; v2 worker currently consumes the `collected_images` library |
| Daily collection plans | `tools/daily_stickers.py`, `enqueue_collections.py` | ✅ | HandNavigate daily job (09:00 IST) |
| AI sticker generation pipeline | `tools/production_worker.py` (partial) | ✅✨ | `backend/worker` full pipeline: library→prescreen→analyze→create→gate→compose |
| Trend intelligence | `tools/trend_intelligence.py` | ✅ | worker `trends` stage |
| Quality gate | `FACTORY_RECOVERY_AND_QUALITY` docs | ✅✨ | `pipeline/gate.py` (local score + optional vision judge) |
| Original artwork (text→image) | `tools/original_artwork.py` | 🟡 | Cloudflare FLUX wiring in `.env.example`; not run yet |
| Scheduled hosted worker | GitHub Actions + Railway | ✅ | `.github/workflows/worker.yml` (gated on `WORKER_SCHEDULE`) |

## Site / Studio / iOS / CI
| Feature | v1 (where) | v2 | v2 (where) / note |
| --- | --- | --- | --- |
| Public marketing/support site | `site/*.html` | ❌ | not ported (can be a simple page later) |
| Creator Studio (web) | `studio/*` | ♻️ | the app's in-phone creator replaces it |
| iOS app | `app/.../ios/*` (KMP) | ✅ | `app/shared/src/iosMain`, `app/iosApp` (CI-built; needs device acceptance) |
| CI (build + tests) | GitHub Actions | ✅ | `.github/workflows/*` (app, schema via PGlite, worker pytest) |
| Dev/release tooling | many `tools/*.py` | ♻️ | `tools/*.ps1` (release, go-live) + worker CLI |

---

## v2-only (new since v1)
- One shared Kotlin Multiplatform codebase for Android **and** iOS (v1 iOS was thinner).
- A real **AI content worker** (Groq vision + captions + quality judge) with per-day budgets and a call ledger.
- **One Groq key at a time** with cooldown/pacing (added 7 Nov) — survives rate limits without burning quota.
- **Private vault** with a confirm gate; vault never syncs or appears in Discover.
- **On-device auto-tagging** (OCR + labels) so search finds stickers by the words on them.
- **Admin offline demo mode**; RLS-enforced admin instead of an open panel.
- **Shared Supabase project with HandNavigate** — collection and generation in one place.
- Migration **ledger** (`stickerdock_migrations`) so each SQL file applies exactly once.

## Missing / Partial — recommended next (highest impact first)
1. **Collection "Collect" admin page / topic control in v2** (🟡) — so topics and the daily queue are managed from the admin, not only in the DB. The design is already parked on `handnavigate-queue`.
2. **Manual cutout touch-up (eraser)** (❌) — auto-cutout misses edges; a simple erase/restore brush rescues many photos. The full brush/paint studio is lower value.
3. **Resumable folder import verified on device** (🟡) — thousands of WebP with cancel/retry/process-death; core to the migration promise.
4. **In-app report button** (♻️) — the server side exists; wiring a report action in Discover closes the safety loop.
5. **Storage usage meter** (❌) — large libraries need to see and manage space (v1 had `StorageBudget`).
6. **Receive-shared-image → create** verified (🟡) — "share to StickerDock" is how people expect to make a sticker.
7. **Saved search history** (❌) — small, high-use convenience.
8. **Original artwork (FLUX) run** (🟡) — text→image packs when collected images are thin; needs Cloudflare keys + a test.
9. **Personal cloud library** (❌) — only if users ask; local backup covers most needs and avoids account/cost.
10. **Public share links for packs** (♻️) — direct pack sharing outside the app was a v1 selling point.
