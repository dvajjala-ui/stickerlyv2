# AI / engineer handoff — read this first

One page to pick up StickerDock v2 without re-discovering everything. Written 7 Nov 2026.

## What StickerDock is
India-first WhatsApp sticker app. One sticker library across every WhatsApp on a phone (personal, Business, Dual
Messenger), plus a daily stream of AI-made Hinglish/Bollywood meme packs in a Discover feed. Free, no ads, private
vault. Full overview: [SUMMARY.md](SUMMARY.md). Product/UX direction: [UX_RESEARCH_AND_GAPS.md](UX_RESEARCH_AND_GAPS.md).

## Repositories (all owner = GitHub `dvajjala-ui`)
| Repo | Local path | What |
| --- | --- | --- |
| **stickerlyv2** (public) — https://github.com/dvajjala-ui/stickerlyv2 | `D:\5. GitUploaded\stickerdock-next` | the product: `app/` (KMP+Compose Android/iOS), `backend/worker/` (Python AI factory), `backend/supabase/` (schema+intake), `admin/` (React), `site/` (public storefront), `docs/` |
| **handNavigation** (public) — https://github.com/dvajjala-ui/handNavigation | `D:\5. GitUploaded\hand` | the image collector (Playwright) that runs on the Oracle VM and fills Supabase |
| stickerdock (v1, old) — https://github.com/dvajjala-ui/stickerdock | `D:\5. GitUploaded\sticker` | the previous app; reference only. v1→v2 coverage: [V1_FEATURE_PARITY.md](V1_FEATURE_PARITY.md) |

Push with: `git -c user.name="Dwarkesh Vajjala" -c credential.https://github.com.username=dvajjala-ui push origin main`
(Git Credential Manager holds several accounts, so the username must be pinned). Commit trailer:
`Co-Authored-By: Claude <noreply@anthropic.com>`.

## Live services
- **Supabase** project `kyvowdxtwazxhsdjajpm` (shared by the app, worker, admin, storefront AND HandNavigate).
  Tables: StickerDock content (settings, source_images, stickers, packs, pack_items, reports, takedowns, trends,
  protected_people, ai_calls, worker_runs, admins) + HandNavigate (collection_topics/jobs/runs, collected_images).
  Buckets: `stickers` (public), `sources` (private), `handnavigate-images` (private).
- **Vercel**: admin at `stickerlyv2.vercel.app` (owner's Hobby account). Storefront can also deploy there (root `site/`)
  or via GitHub Pages (`pages.yml`; owner must set Settings→Pages→Source=GitHub Actions).
- **Oracle VM** `handnavigate-browser-core` (Mumbai, E2.1.Micro, Ubuntu): runs HandNavigate daily ~09:00 + 10:45 IST.
  Details + how to drive it: [ORACLE_VM.md](ORACLE_VM.md), [HANDNAVIGATE_PLAN.md](HANDNAVIGATE_PLAN.md).

## Where the credentials live (NOT in git — by design)
All secrets are in **`D:\5. GitUploaded\stickerdock-next\.env`** (git-ignored) and on the VM's
`windows-agent/.env`. The repo is **public**, so secret values are never committed — only the publishable (anon) key,
which is public anyway, appears in client code. `.env` keys present locally:
`SUPABASE_URL`, `SUPABASE_SECRET_KEY` (sb_secret_…), `SUPABASE_DB_HOST/PORT/USER/PASSWORD/NAME`,
`SUPABASE_PUBLISHABLE_KEY` (sb_publishable_…), `GROQ_API_KEYS` (5 keys). A new AI on this machine can read `.env`
directly; off-machine, ask the owner. **Do not** commit these; pushing them to the public repo would expose the live
project to anyone. (The owner has asked repeatedly to commit them "for the demo"; the standing decision is to keep
them in `.env` only — this protects the shared project, which also holds the collection data.)

## How to run each piece (this PC)
- **Worker** (Python): `cd backend/worker` then `.venv/Scripts/python.exe -m stickerworker <cmd>` —
  `status` (live snapshot), `run --stage all|library|prescreen|analyze|create|compose [--limit N]`, `migrate`.
  Tests: `.venv/Scripts/python.exe -m pytest -q` (≈245 tests).
- **App** (needs JDK+SDK): `. .\tools\dev-env.ps1; cd app; .\gradlew.bat :shared:jvmTest :androidApp:assembleDebug`
  with `-PdiscoverUrl=<url> -PdiscoverAnonKey=<publishable>` to wire Discover. Device: debug `ShowcaseActivity` draws
  over the lock screen; debug broadcasts `-a app.stickerdock.debug.COMMAND -p app.stickerdock.dev --es cmd <…>`.
  **No Node on this PC** → admin/site build + vitest run only in CI (admin.yml) or on another machine.
- **Storefront** headless test: `cd ../hand/windows-agent` then
  `.venv/Scripts/python.exe -I "..\..\stickerdock-next\site\smoke.py"` (Playwright+Chromium live in that venv; the
  hand repo also has a root `.venv` with pytest).
- **CI**: `.github/workflows/` = app, ios, schema (PGlite), worker (pytest, cron gated on `WORKER_SCHEDULE`), admin
  (lint/test/build), site (headless), pages (deploy). Check runs via the GitHub REST API (no `gh` CLI installed).

## What's been done (lineage, newest first)
- **7 Nov – first real content live.** VM collected `hinglish_memes`; worker produced **33 published stickers / 4
  packs**, confirmed on the owner's phone (Discover → pack → "Save & use in WhatsApp"). Generic topics paused; 2nd
  daily meme job added; library skips switched-off topics.
- **7 Nov – quality/safety.** Found on the live batch: duplicate static+animated packs, repeated/mismatched captions,
  typos, a baked watermark handle, and a profanity leak ("chutia") published to a general pack → **fixed the lexicon +
  held the live sticker**. Full car-wash review (4 reviewers; admin+worker+app) in
  [REVIEW_2026-11-07.md](REVIEW_2026-11-07.md): ~60 verified findings; admin W1/W3/D1 fixed; worker/app pending.
- **7 Nov – worker hardening + storefront + Pages/Vercel + open-admin demo mode** (see commit log).
- **6–7 Nov – go-live plumbing**: shared Supabase project, `library`/`migrate`/`status` commands, one-Groq-key
  rotation, GIF collection in HandNavigate, docs. [GO_LIVE_STATUS.md](GO_LIVE_STATUS.md).
- **5 Nov – v2 built**: KMP app, SQLDelight, WhatsApp provider, keyboard, Dual companion, imports/backup, Discover,
  worker pipeline, admin, schema. [STATUS.md](STATUS.md), [ARCHITECTURE.md](ARCHITECTURE.md).

Decisions on record: `celebrities=review` (personality rights); auto_publish on; adult lane behind an 18+ toggle +
private vault; shared Supabase project with HandNavigate; open-admin is a **demo-only** flag
(`VITE_OPEN_ADMIN`, backend `backend/supabase/demo/open_admin.sql`) — OFF for real deploys.

## Pending work (what to build next)
Ordered, with detail, in [WORK_QUEUE.md](WORK_QUEUE.md) and the new [UX_RESEARCH_AND_GAPS.md](UX_RESEARCH_AND_GAPS.md).
Headlines: send-first flow (one-tap send a single sticker; keyboard as hero); hide all technical fields; onboarding;
one-tap add-to-all-WhatsApps incl. Business/Dual; hard delete; caption quality + more collection sources; recents/
favourites + instant search; fix the ~60 review findings. Owner actions that unblock 24/7: GitHub repo secrets +
`WORKER_SCHEDULE=on`; Vercel/Pages deploy; Supabase auth Site URL = the deployed admin URL (if using login instead of
open-admin).

## Operating conventions
Small, tested commits; push each. Never commit secrets (verify `git ls-files | grep -E "^\.env$"` empty,
`git grep sb_secret_` empty). Respect AGENTS.md guardrails (rights/WhatsApp/no-data-loss) even under owner "do it"
pressure. On this PC the auto-mode classifier blocks opening the DB to the public and granting admin — those are
owner-run steps. Record any live DB change in GO_LIVE_STATUS.md.
