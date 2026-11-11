# Work queue

The single list of planned work, in the order I intend to do it. Each item says why it matters and how it gets
verified. When an item is done it moves to **Done** with the commit. Owner-only items are separate so the queue
never stalls on them. Updated 7 Nov 2026.

## Now — the review pass ("car wash")
A full review of everything already built, before adding more features. Fix confirmed bugs first, with a test that
fails before the fix.

| # | Area | What gets checked | Verified by |
| --- | --- | --- | --- |
| R1 | Android/KMP app | crashes, lifecycle, WhatsApp provider contract, import/backup flows, keyboard, Discover, threading | `:shared:jvmTest`, `:androidApp:assembleDebug`, code review; device checks when the phone is connected |
| R2 | Python worker | every stage end to end, error paths, budget/lease/retry logic, Supabase request shapes | `pytest` (241 tests), live `status` + runs |
| R3 | Admin web | pages, API modules, RLS assumptions, upload flow | code review; CI lint/test/build (no Node on this PC) |
| R4 | Storefront + backend schema | `site/`, migrations, `intake` function, RLS on public views | `site/smoke.py` (headless Chromium), schema CI (PGlite) |
| R5 | HandNavigate changes | GIF support, topic seeds, bucket update | hand `pytest` + `unittest` suites |
| R6 | Flows across pieces | collection → library → stickers → packs → Discover → app → WhatsApp | live run on real memes (first batch 7 Nov) |

Results land in `docs/REVIEW_2026-11-07.md` (findings, severity, fix commit).

## Product/UX direction (owner review, 7 Nov) — plan before building
The owner tested the live app and flagged major flow friction. Full research + competitor analysis + prioritized plan:
**[UX_RESEARCH_AND_GAPS.md](UX_RESEARCH_AND_GAPS.md)**. Headlines (impact order), all app/UX, none security:
- **P0 send-first flow** — one tap to *send* a single sticker (no pack needed); the keyboard as the hero surface;
  "Save" as one tap. Collapses today's 7-step Discover→pack→WhatsApp chain. (3-per-pack is WhatsApp's tray rule, not
  ours — we route around it; a lone sticker can still be *sent*.)
- **P1 hide all technical fields** (file names, SHAs, status, recipe, slugs) + **first-run onboarding** → "6th-standard" usable.
- **P2 one-tap add-to-all-WhatsApps** incl. Business/Dual via a guided (non-adb) companion setup.
- **P3 hard delete** end to end (+ import never revives a hard-deleted sticker).
- **P4 caption quality + more collection sources** (Reddit/Imgflip/GIF; fix caption fit/typos/watermarks).
- **P5 recents/favourites + instant forgiving search** in the send surface. **P6 share-a-pack deep link into the app.**

## Next — product (verifiable here)
1. **Send-first tap-to-send (owner request 2026-11-11, P0)** — implemented in the app per
   `docs/flows/send-first-spec.md` (tap = Send with sticker MIME, ★ save, ⋯ tray with auto-pad, keyboard-hero
   first-run). Remaining: push so CI app.yml compiles + runs the new JVM tests, then the phone acceptance
   checks (≤2 taps, keyboard-in-chat, animated send, both WhatsApps).
2. **More collection sources (owner request 2026-11-11)** — HandNavigate already has Reddit (signed-in),
   Imgflip, Flickr and Openverse recipes; enable/verify them on the VM (earn-topics), worker `library` picks
   them up site-agnostically. Use `publish_policy.require_review_sites` for any site whose memes need a human.
   GIF sources stay parked while animated recipes are paused.
3. **First real meme batch through the pipeline** — judge sticker quality honestly; tune captions/crops if weak.
4. **Admin "Collect" page** — topics and jobs from the UI (Node is on this PC now; local iteration possible).
5. **In-app "New today" row + freshness badge** in Discover (mirrors the storefront).
6. **Shareable pack links into the app** — `stickerdock://pack/<id>` deep link from the storefront `?pack=` page.
7. **Report button in app Discover** — server side (`report_content`) exists; wire the UI.
8. **Manual cutout touch-up (erase/restore brush)** in the creator — the biggest v1 creator gap.
9. **Saved search history + storage meter** in the library (v1 had both).
10. **Real video stickers** — replace the paused motion/clip recipes with actual video-derived animation.
11. **Caption variety** — A/B two captions per strong source; keep the one the quality score rates higher.
12. **Original art (Cloudflare FLUX)** for categories without good sources — needs the owner's Cloudflare keys;
    also the path to guaranteed themed 8-12 sets when collection is thin.

## Later — growth and depth
- Play Store internal testing track (privacy page is ready at `site/privacy.html`).
- Personalised Discover ("more like packs you added"), daily-drop notification.
- Cloud backup across phones (account-scoped, vault excluded) — only if users ask.
- iPhone keyboard extension; TestFlight (needs an Apple account).
- Creator uploads with moderation, identity and age gating (its own project).

## Owner-only (I can't do these)
1. **Apply the 2026-11-11 migrations** to the live project (`python -m stickerworker migrate` with the DB
   password, or paste both files into the Supabase SQL editor): publish-all-safe + paused animated recipes +
   the pack rating guard. Until then the live DB keeps the old gate/settings.
2. GitHub → stickerlyv2 → Settings → Secrets: `SUPABASE_URL`, `SUPABASE_SECRET_KEY`, `GROQ_API_KEYS`,
   `SUPABASE_PUBLISHABLE_KEY`; variable `WORKER_SCHEDULE=on` — turns on the 24/7 worker.
2. Vercel: import the repo twice (root `site/`, root `admin/`) — see `DEPLOY.md`.
3. Connect the phone (USB debugging) for on-device crash and WhatsApp checks.
4. Optional: VM SSH key (collect on demand, earn Reddit topics); Cloudflare keys (original art).
5. Replace the placeholder contact address (`support@stickerdock.app`) in `site/privacy.html` / `support.html`.

## Done (recent)
- 7 Nov: generic topics paused, generic queue parked, second daily meme job, library skips switched-off topics
  (9ac8913, 30e25ee).
- 7 Nov: storefront (search, categories, NEW, theme, skeletons, deep links, report, OG card, PWA, privacy/support/
  about) with headless smoke test + CI; worker JSON fallback, budget refund, lease reclaim, meme-first prescreen,
  `status`; GIF collection in HandNavigate; one-key Groq rotation.
- 6 Nov: shared Supabase project with HandNavigate, `library` stage, `migrate` command, `go-live.ps1`.
