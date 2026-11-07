# Session log — 6-7 Nov 2026 (full record for handoff)

The owner is going offline and can't communicate for a while. This captures **everything from this working session**:
every request/instruction the owner gave, every decision, what was done (with commits), and what's pending — so a new
AI/engineer loses nothing. Pairs with [AI_HANDOFF.md](AI_HANDOFF.md) (durable state) and [INDEX.md](INDEX.md).

## Standing instructions the owner gave this session (treat as ongoing)
1. **Work autonomously and continuously** ("you are the product owner", "I approve all your decisions", "keep working
   until the limit", "fail and retry", "research the internet"). Keep a loop of find-gap → research → plan → (safe,
   tested) fix → commit → push.
2. **Build a 100%-working app first; security later** — but the owner repeatedly asked to commit secret keys to git.
   **Standing decision (upheld): never commit secrets to the public repo.** All creds live in the git-ignored `.env`
   (complete: SUPABASE_URL, SUPABASE_SECRET_KEY, DB_*, PUBLISHABLE_KEY, GROQ_API_KEYS ×5) and on the VM. Only the
   public publishable key appears in client code. This protects the live shared project (also holds HandNavigate data).
   The auto-mode classifier also blocks opening the DB / granting admin from here — those are owner-run.
3. **User-centric, "6th-standard-kid" simple.** Hide all technical fields; shortest path to send/add. Research real
   competitors, not guesses.
4. **Keep docs synced**; write multiple research/planning MD files so other AIs understand pattern/logic/stack/problem/
   solution; keep a work queue; record live DB changes.
5. **No security/key changes requested as "features"**; focus on functionality + UX.
6. Model note: commit trailer `Co-Authored-By: Claude <noreply@anthropic.com>`.

## Specific requests raised this session (status)
- **Keys pasted** (publishable, DB pass, 5 Groq) → put in `.env`; secret key given later → pipeline runs. ✅
- **Groq: one key at a time, cooldown/rotation** so it never trips limits → implemented + tested. ✅
- **Add meme/GIF/adult topics** (dark, adult humour, dirty jokes, movies, series, songs, girls/boys/bro-code/love,
  GIFs) to Pinterest+Reddit → 16 topics added; generic topics paused; 2nd daily meme job; GIF collection code added. ✅
- **Content/jokes md** → CONTENT_STRATEGY.md. ✅
- **Deploy the site + admin; CRUD; add-from-local; AI → users with no confirmation** → storefront built+deployed (Pages
  workflow + Vercel guide); admin CRUD exists; auto_publish on. Admin **open-admin demo mode** added per owner (Plan B;
  VITE_OPEN_ADMIN + backend/supabase/demo/open_admin.sql, applied live). ✅ (owner must set the Vercel env var + redeploy)
- **Feature parity vs old app** → V1_FEATURE_PARITY.md. ✅
- **Which Oracle instance / what to open** → ORACLE_VM.md. ✅
- **Full code review / "car wash" (crashes, gaps, flow, optimization)** → 4 reviewers; ~60 verified findings in
  REVIEW_2026-11-07.md; several fixed (see below); rest pending. ✅ (ongoing)
- **Device test on the phone** → debug APK built with Discover, installed on SM-S928B; **Discover shows real packs**;
  pack → "Save & use in WhatsApp" works; no crashes. ✅
- **First real content** → VM collected `hinglish_memes`; worker produced **33 published stickers / 4 packs**. ✅
- **Admin login broken (magic link → localhost)** → root cause = Supabase Site URL; owner chose "remove auth" → Plan B
  open-admin shipped; owner account exists + confirmed; **owner still must** set Site URL (if using login) or the Vercel
  env var (for open-admin). ✅ (owner step remains)
- **Timer / resume loop across limits** → used ScheduleWakeup earlier; mechanism is best-effort (platform limits).
- **High-level planning only (no code), then "add solutions"** → produced the research/planning library, then resumed
  safe tested fixes.
- **Watermarks on images (remove them), weak animated stickers (just a bounce), dedupe tool lacks features, more sources,
  deeper research, multiple md files** → research library written (see below); caption/handle fix shipped.

## What was built/fixed this session (by area, newest first)
**Content live:** memes collecting (meme topics verified, generics paused, 2nd job), pipeline run → 33 published/4 packs,
verified on the phone. Profanity leak ("chutia") found + lexicon fixed + live sticker held. Caption tidy (strip
@handles/URLs, tame stretched vowels) shipped.
**Worker fixes (all pytest-tested):** one-Groq-key rotation; vision json_object fallback + budget refund; self-healing
analyze leases (reclaim_stale); meme-aware prescreen text ranking; `status` command (+ sticker counts); library skips
switched-off topics; dry-run no longer mutates prod (P10); trends don't re-enable owner-disabled (P11); text-bonus no
longer lifts publish quality (P8); lexicon catches "chutia"/abuse variants; caption tidy.
**Admin fixes (CI-verified):** won't publish before rules load (W1), won't vanish live packs (W3), won't lose edits
(D1); open-admin demo mode; base-path support; build-break fixes.
**Storefront:** full rebuild (hero/search/categories/NEW/theme/skeletons/shareable ?pack=/report/OG/PWA/privacy-support-
about), headless smoke test + CI.
**HandNavigate:** GIF + animated-WebP collection + bucket mime update; +16 topics.
**Deploy:** GitHub Pages workflow (storefront + admin); Vercel guide; admin env-type + stray-paren build fixes.
**Docs written:** SUMMARY, AI_HANDOFF, INDEX, WORK_QUEUE, PRODUCT_REVIEW, UX_RESEARCH_AND_GAPS, GROWTH_AND_MONETIZATION,
PLAY_STORE_LAUNCH, OPERATIONS_AND_RELIABILITY, GO_LIVE_STATUS, CONTENT_STRATEGY, ORACLE_VM, V1_FEATURE_PARITY,
REVIEW_2026-11-07, flows/send-first-spec, flows/onboarding-spec, research/{README,architecture-and-patterns,watermark-
handling,animated-stickers,deduplication,content-quality}.

## Live DB changes made (shared Supabase `kyvowdxtwazxhsdjajpm`)
Applied: v2 schema (migrate); verified 11 meme topics; paused 6 generic topics; parked ~167 generic queued sources
(status=rejected, reversible); 2nd collection job (10:45 IST); celebrities=review; held 2 profane stickers;
`backend/supabase/demo/open_admin.sql` applied (anon can read/write content tables — DEMO; reverse with its DROP block).
All recorded in GO_LIVE_STATUS.md.

## Owner actions still required (nothing else blocks these)
1. **24/7 worker:** GitHub → stickerlyv2 → Settings → Secrets: SUPABASE_URL, SUPABASE_SECRET_KEY, GROQ_API_KEYS,
   SUPABASE_PUBLISHABLE_KEY; variable WORKER_SCHEDULE=on.
2. **Deploy:** Vercel (admin built; set VITE_OPEN_ADMIN=true to open it) and/or GitHub Pages (Settings→Pages→Source=
   GitHub Actions). Storefront root `site/`, admin root `admin/`.
3. **If using login instead of open-admin:** Supabase → Auth → URL Configuration → Site URL = the deployed admin URL;
   and `insert into public.admins (user_id,email) values ('d9ec0d38-fc2e-4c6c-b611-77f5935e6a68','dvajjala@gmail.com');`
4. Replace placeholder contact `support@stickerdock.app` in site/privacy.html + support.html.
5. Optional: VM SSH key (collect on demand / earn Reddit topics); Cloudflare keys (original AI art).

## What to build next (ordered) — for the next AI
From WORK_QUEUE.md + UX_RESEARCH_AND_GAPS.md + research/:
1. **Send-first flow (P0)** — flows/send-first-spec.md: tap=send a single sticker, keyboard as hero, hide pack chore.
2. **Hide technical fields + onboarding (P1)** — flows/onboarding-spec.md.
3. **One-tap add-to-all-WhatsApps incl. Business/Dual (P2).**
4. **Hard delete (P3)** — end to end; import never revives.
5. **Content quality + more sources (P4)** — research/content-quality.md (caption fit/diversity/A-B), watermark
   crop/cover (research/watermark-handling.md), real GIF motion + stop auto-twinning statics (research/animated-stickers.md).
6. **Dedupe cluster + keep-best (research/deduplication.md).**
7. **Fix remaining REVIEW_2026-11-07 findings** (app A1 version-guards is a crash blocker for Android 9-12; worker
   P1/P2/P3/P5/P6/P7/P9/P12; admin W2/W4-W8/D2/D3/F/N).
8. **Operations health panel (OPERATIONS_AND_RELIABILITY.md)** so stalls are visible.
9. **Play Store (PLAY_STORE_LAUNCH.md)** — biggest growth unlock; needs the A1 crash fix + AAB first.

## How to continue (for the next AI)
Read AI_HANDOFF.md → research/architecture-and-patterns.md → this log → WORK_QUEUE.md. Run worker tests with
`backend/worker/.venv`; storefront with `site/smoke.py` (Playwright in hand/windows-agent/.venv); app with Gradle +
the connected phone; admin via CI (no Node on this PC). Keep commits small, tested, pushed; never commit secrets;
hold the AGENTS.md guardrails.
