# StickerDock v2

One sticker library for every WhatsApp on your phone — WhatsApp, Business, Dual Messenger and WhatsApp's second
account — always in sync, plus fresh AI-made Hinglish reaction stickers every day.

Clean rebuild of [StickerDock v1](../sticker). Start with [docs/PRODUCT.md](docs/PRODUCT.md),
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/CONTENT_POLICY.md](docs/CONTENT_POLICY.md) and
[docs/STATUS.md](docs/STATUS.md) (what is verified). Owner to-dos: [docs/OWNER_REQUESTS.md](docs/OWNER_REQUESTS.md).

| Folder | What |
| --- | --- |
| `app/` | Kotlin Multiplatform app: `shared/` (library, WebP, Discover, Compose UI) + `androidApp/` (WhatsApp provider, keyboard, Dual Messenger companion, imports) |
| `backend/supabase/` | Postgres schema, RLS, public Discover API, `intake` Edge Function |
| `backend/worker/` | Python AI sticker factory (pre-screen → Groq understanding → policy → recipes → packs) |
| `admin/` | React admin panel (sources, stickers, packs, reports/takedown, trends, settings) |
| `site/` | Public Discover storefront (static page; reads published packs/stickers with the publishable key) |

**Start here (new AI/engineer):** [docs/AI_HANDOFF.md](docs/AI_HANDOFF.md) (lineage, repos, how to run, what's done, pending).
**Product/UX plan:** [docs/UX_RESEARCH_AND_GAPS.md](docs/UX_RESEARCH_AND_GAPS.md) (competitor research, flow gaps, send-first fix).
**Research library:** [docs/research/](docs/research/) (architecture & patterns, watermark handling, animated stickers, deduplication).
**Going live & operating:** [docs/GO_LIVE_STATUS.md](docs/GO_LIVE_STATUS.md) (what's live, owner actions),
[docs/DEPLOY.md](docs/DEPLOY.md) (Vercel for site + admin), [docs/ORACLE_VM.md](docs/ORACLE_VM.md) (the collector VM).
**Product & planning:** [docs/PRODUCT_REVIEW.md](docs/PRODUCT_REVIEW.md) (user-centric review + roadmap),
[docs/CONTENT_STRATEGY.md](docs/CONTENT_STRATEGY.md) (topics, voice, safety),
[docs/V1_FEATURE_PARITY.md](docs/V1_FEATURE_PARITY.md) (v1→v2 audit),
[docs/HANDNAVIGATE_PLAN.md](docs/HANDNAVIGATE_PLAN.md) (collection integration).

Operator snapshot anytime: `cd backend/worker && python -m stickerworker status` (queue, published content, today's AI spend).

## Build the Android app (Windows, this PC)
```powershell
. .\tools\dev-env.ps1
cd app
.\gradlew.bat :shared:jvmTest :androidApp:assembleDebug
adb install -r androidApp\build\outputs\apk\debug\androidApp-debug.apk
```
Discover is optional at build time: `-PdiscoverUrl=https://<project>.supabase.co -PdiscoverAnonKey=<anon key>`.

## Dual Messenger (one-time, from a PC with adb)
```
adb shell pm list users                      # find the DUAL_APP user id (95 on Samsung)
adb shell pm install-existing --user 95 app.stickerdock
adb shell pm grant app.stickerdock android.permission.INTERACT_ACROSS_USERS
adb shell pm grant --user 95 app.stickerdock android.permission.INTERACT_ACROSS_USERS
```
(`app.stickerdock.dev` for debug builds.) The copy in Dual Messenger has no library of its own; it serves that
profile's WhatsApp from the main library.
