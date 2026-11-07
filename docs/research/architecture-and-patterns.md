# Architecture, patterns & reasoning

The mental model for the whole system, so any tool/AI understands the stack, the data flow, why each choice was made,
and the top problems → solutions. Pairs with `docs/ARCHITECTURE.md` (component detail) and `AI_HANDOFF.md` (how to run).

## The one-sentence system
HandNavigate (a browser robot on a VM) collects images into Supabase → a Python worker turns them into WhatsApp
stickers and packs → the phone app and a web storefront read those from Supabase → the app pushes packs into WhatsApp.
An admin web panel supervises it all. One shared Supabase project is the backbone.

## Stack (and why)
- **App: Kotlin Multiplatform + Compose Multiplatform** (Android now, iOS shares the core). *Why:* one codebase for
  both phones; native WhatsApp ContentProvider + IME need real Android. SQLDelight for a typed local DB; Coil 3 images;
  WorkManager for background import/auto-tag; ML Kit (on-device) for OCR/labels/cutout — *on-device so it's free and
  private.*
- **Backend: Supabase** (Postgres + RLS + Storage + Edge Functions). *Why:* free tier, Postgres is the source of truth,
  Row-Level-Security lets the phone read public content with a public key and nothing else. Shared with HandNavigate so
  collection and generation live in one place.
- **Worker: Python** (Pillow/ffmpeg imaging, httpx to Supabase REST + Groq). *Why:* best imaging ecosystem; runs as a
  GitHub Actions cron (free) or locally. **Groq** for vision+text LLM *because it's free/fast*; one key at a time with
  cooldown (separate free accounts, never stacked).
- **Admin + storefront: React/Vite (admin) and one static HTML page (storefront).** *Why:* static hosting (Vercel/Pages),
  no server; all auth/permission via Postgres RLS.
- **Collector: HandNavigate** (Python + Playwright) on an Oracle Always-Free VM. *Why:* logged-in Pinterest/Reddit need
  a real signed-in browser a person occasionally unlocks; the VM is free and always-on.

## Data flow (happy path)
`collection_topics` (what to collect) → HandNavigate daily job → `collected_images` + `handnavigate-images` bucket →
worker **library** copies new rows to `source_images` + `sources` bucket → **prescreen** (hash, dup, takedown, OCR) →
**analyze** (Groq vision: tags, crop, rating, safety) → **create** (cutout, caption, encode 512² WebP, quality gate,
policy decision → published/held/rejected) → **compose** (group into packs ≥3) → `discover_stickers`/`discover_packs`
views → app Discover + storefront → user saves → app writes local library → app serves WhatsApp via the ContentProvider.

## Core patterns (the "logic" to preserve)
- **Status machine on `source_images`:** `new → screened → analyzing → analyzed → used/rejected/failed`, each stage
  claims rows with a **lease** (`locked_until`) + `attempts` cap so a crash self-heals and nothing is processed twice.
  *(Known bugs: attempts shared across stages; analyze can orphan rows — see REVIEW P1/P2.)*
- **Budget + ledger:** daily Groq spend is capped; the `ai_calls` ledger is the cross-process source of truth so the
  cron can't overspend. *(Known: ledger undercounts — P13.)*
- **RLS as the only gatekeeper:** the phone/storefront use the **publishable** key and can read only published content
  (via `discover_*` views); everything private needs the service role (worker) or an admin session. The **secret key is
  never shipped to a client** — the single hard security rule.
- **Soft-delete + 7-day purge + undo** everywhere, with dHash fingerprints for dedupe. *(Owner wants hard-delete too —
  see deduplication.md / UX plan.)*
- **Static vs animated never mix in a pack;** packs keyed on `(series, kind, rating)`; publish only at ≥3 (WhatsApp's
  rule). Version bumps on every pack change so WhatsApp re-reads.
- **On-device AI in the app; cloud AI only in the worker.** Keeps the app free/private and the heavy lifting server-side.

## Top problems → solutions (cross-component, prioritized)
1. **Developer-shaped UX (long add-to-WhatsApp chain, visible internal fields).** → *send-first* flow, hide technical
   fields, onboarding. See `UX_RESEARCH_AND_GAPS.md`. **Biggest lever.**
2. **Weak content (few sources, watermarks, synthetic bounce, caption typos/repeats).** → more + cleaner sources
   (Reddit/Imgflip/Giphy), watermark handling (`watermark-handling.md`), real motion + no auto-twins
   (`animated-stickers.md`), tighter caption prompt.
3. **Reliability bugs in the pipeline** (stuck rows, budget undercount, dup variants, dry-run writing to prod). →
   REVIEW_2026-11-07.md P-list; all pytest-fixable.
4. **Dedupe is pairwise/slow and sometimes wrong.** → cluster + keep-best + correctness fixes (`deduplication.md`).
5. **Second-WhatsApp (Business/Dual) setup needs adb.** → one-tap guided companion (UX plan §7).
6. **Deploy/ops friction** (magic-link redirect, Pages enablement, worker cron secrets). → owner one-time steps,
   documented in GO_LIVE_STATUS.md / DEPLOY.md.

## What runs where (so nothing is mis-placed)
- **On the VM:** only HandNavigate (browser collection). ~1 GB RAM — no ML models there.
- **In the worker (GitHub cron or this PC):** all image/AI generation; Groq calls; heavy Pillow/ffmpeg.
- **In the app (phone):** local library, on-device OCR/labels/cutout, WhatsApp provider, keyboard.
- **In the browser (admin/storefront):** read/manage via Supabase; **never** the secret key; never heavy compute.
- **Not yet placed:** watermark-removal ML + catalog-dedupe clustering → a separate batch tool on a bigger box, feeding
  Supabase (see the two research files); do **not** add these to the VM or the free worker box.

## Non-negotiables
Secret key never in a client or the public repo. Rights/WhatsApp/no-data-loss guardrails (AGENTS.md) hold even under
"just do it". Schema + backup-format compatibility preserved. Small, tested, pushed commits.
