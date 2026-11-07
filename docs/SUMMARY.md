# StickerDock v2 — everything in one page

The one place to see what this is, what's built, what's live, and what's next. Links go to the detailed docs.
Updated 7 Nov 2026.

## What it is
One sticker library for every WhatsApp on a phone (personal, Business, Dual Messenger), plus a daily stream of
fresh, funny, India-first (Hinglish/Bollywood) sticker packs made by an AI pipeline. Free, no ads, no watermarks, a
private vault for personal stickers. Positioning and why users choose us: [PRODUCT_REVIEW.md](PRODUCT_REVIEW.md).

## The four parts
- **App** (`app/`): Kotlin Multiplatform + Compose, Android + iOS. Library, packs, shelves, favourites, search,
  per-sticker tags, import (SAF folders, ZIP/.wastickers, shared images), create (photo cutout + caption, video→
  animated), sticker keyboard (IME), WhatsApp ContentProvider, Dual Messenger companion, backup/restore, Discover,
  18+ lane + private vault, on-device auto-tagging (OCR + labels), duplicate finder.
- **Worker** (`backend/worker/`): Python AI factory — `trends → library → prescreen → analyze (Groq vision) →
  create (cutout, caption, quality gate, policy) → compose (packs)`. One Groq key at a time with cooldown; daily
  budgets; `migrate` and `status` commands.
- **Backend** (`backend/supabase/`): Postgres schema + RLS, public Discover views/RPCs, the `intake` Edge Function,
  private `sources`/`handnavigate-images` buckets, public `stickers` bucket.
- **Web**: public **storefront** (`site/`) and the **admin** panel (`admin/`, React) — review sources, upload from
  your PC, edit/publish stickers and packs, reports/takedowns, trends, settings, held-review.
- **Collector**: HandNavigate on an Oracle VM collects verified Pinterest/Reddit images daily into Supabase
  ([ORACLE_VM.md](ORACLE_VM.md)). v1→v2 feature coverage: [V1_FEATURE_PARITY.md](V1_FEATURE_PARITY.md).

## Live right now (shared Supabase project `kyvowdxtwazxhsdjajpm`)
- Schema applied; secret key in a git-ignored `.env`; pipeline proven end to end.
- Collection switched from generic photos to **memes**: 11 meme topics verified, generics paused, a 2nd daily job,
  `celebrities=review` to protect personality rights. First real batch: 20 `hinglish_memes` collected 7 Nov.
- CI green: app, iOS, schema, worker, admin, storefront (headless-browser), Pages build. Details:
  [GO_LIVE_STATUS.md](GO_LIVE_STATUS.md).

## Deployment status (honest)
- **Storefront + admin are built and ready; not yet on a public URL.** I can't deploy to your Vercel (needs your
  account). I set up **GitHub Pages** instead — it builds on GitHub and needs one setting from you:
  **repo → Settings → Pages → Source = "GitHub Actions"**, then it auto-deploys to:
  - storefront: `https://dvajjala-ui.github.io/stickerlyv2/`
  - admin: `https://dvajjala-ui.github.io/stickerlyv2/admin/`
- Vercel is the fully-tested alternative ([DEPLOY.md](DEPLOY.md)). The admin needs you to sign in once (magic link)
  and be added to `public.admins` to use it.

## The quality pass ("car wash") — in progress
Four reviewers went over everything. Findings and fix status: [REVIEW_2026-11-07.md](REVIEW_2026-11-07.md).
- **Admin:** fixed so far — won't publish before rules load, won't vanish live packs, won't lose edits (W1/W3/D1).
- **Worker:** 13 verified bugs with repros (stuck rows, budget undercount, 18+/adult rating leaks, dup reprocessing)
  — fixing next; all pytest-verifiable.
- **App:** 34 findings — biggest is missing Android-version guards (crashes/dead WhatsApp detection on Android 9–12).
- Device check on your SM-S928B: app launches, DB/migrations clean, import error-path solid, no crashes.

## Plan ahead
Ordered list with how each is verified: [WORK_QUEUE.md](WORK_QUEUE.md). Short version:
1. Finish the car-wash fixes (worker → app → admin), each with a test.
2. First real meme stickers live on Discover + storefront; judge and tune quality.
3. In-app freshness + report button + manual cutout eraser; smoother animations; caption variety.

## What only you can do
1. Repo → Settings → Pages → Source = "GitHub Actions" (gives live URLs), **or** import to Vercel.
2. For 24/7 worker: repo secrets (`SUPABASE_URL`, `SUPABASE_SECRET_KEY`, `GROQ_API_KEYS`, `SUPABASE_PUBLISHABLE_KEY`)
   + variable `WORKER_SCHEDULE=on`.
3. Sign in to the admin once and add yourself to `public.admins`.
4. Replace the placeholder contact `support@stickerdock.app` in `site/privacy.html` and `site/support.html`.
5. Optional: VM SSH key (collect on demand), Cloudflare keys (original AI art).
