# Operations & reliability

A gap not yet raised: the system has several single points of failure that fail **silently**. If collection or the
worker stops, Discover just goes stale and no one notices for days. This plans health, alerting, failure modes, and
content lifecycle. Planning only — no code.

## Failure modes (and how they currently look)
| What breaks | Why | Today's symptom | Blast radius |
| --- | --- | --- | --- |
| **Oracle VM stops / IP reclaimed** | Always-Free idle reclamation; ephemeral public IP changes on stop/start | collection_runs stop appearing; no alert | no new content |
| **Pinterest/Reddit login challenge** | logged-in session needs a human occasionally | runs return ACCESS_REQUIRED / 0 images; job auto-disables on non-VERIFIED | content dries up quietly |
| **Worker cron off / failing** | `WORKER_SCHEDULE` not on, or secrets missing, or a stage crash | source_images pile up unprocessed; 0 new stickers | Discover frozen |
| **Groq daily quota hit** | free-tier vision cap | analyze stops at the budget; rows wait | partial day |
| **Supabase free limits** (egress/storage/rows) | library re-downloads skipped rows (perf note); storage grows | slowdowns, 4xx | degraded |
| **A stage silently no-ops** | e.g. OCR deps missing in CI (P5), library 200-row window (P12) | looks "ok" but quality/coverage drops | subtle quality loss |

## What's missing
1. **No alerting.** `worker_runs` and `collection_runs` record status, but nothing watches them. The only check is the
   manual `stickerworker status`.
2. **No heartbeat / freshness SLO.** No "last successful collection" / "last published pack" age that trips a warning.
3. **No single health view** for the owner (non-technical) — "is everything running?" in plain words.
4. **Ephemeral VM IP** isn't reserved, so a restart breaks SSH + any assumptions.

## Resolution path (easiest first; all cheap)
1. **Freshness self-check in the worker.** Extend `status` into a `health` command / a tiny daily job that flags:
   last collection > 36 h ago, last published pack > 48 h, screened backlog > N with 0 analyzed today, or any
   `worker_runs.ok = false` in the last day. Output a plain verdict: "Healthy" / "Collection stalled" / "Worker not
   running".
2. **Push the verdict somewhere the owner sees it** — cheapest: write a `health` row + a small badge on the admin
   Overview; next: a free webhook/email (Supabase edge function or a GitHub Action that posts to the owner) once a day
   or on red. No new paid service.
3. **Login-challenge alert from the VM:** when a collection_run returns ACCESS_REQUIRED, flag it loudly (the human must
   re-login). Today the job just disables — surface "your Pinterest/Reddit login needs a tap".
4. **Reserve the VM public IP** and document SSH; stop silent breakage on restart (ORACLE_VM.md).
5. **Guard the silent no-ops** from the review: always install OCR deps (P5); anti-join library select so backlog > 200
   isn't missed (P12); make `status` report stuck/`attempts>=5` rows (P2).
6. **Backups of the backbone:** the Supabase project is the source of truth — enable/verify Supabase's backups; keep the
   signing keystore backed up (already noted in release.ps1). Losing either is unrecoverable.

## Content lifecycle (freshness without bloat)
Not yet handled: packs/stickers accumulate forever; Discover will bloat and go stale-looking.
- **Retire** low-engagement or old packs from the top of Discover (keep them searchable, drop them from "Trending").
- **De-dup at the catalog level** before publishing (research/deduplication.md) so the same meme isn't published twice.
- **Cap per-category volume** so one topic can't flood Discover.
- **"New today" vs "Evergreen"** surfaces: fresh drops up top, a searchable evergreen library behind.
- **Takedowns & rights:** the flow exists; add a periodic check that taken-down SHAs never re-enter via collection.

## A plain-words health panel (for a non-technical owner)
Admin Overview should say, in one glance:
- "Collecting: ✅ last batch 2 h ago (hinglish memes, 20 images)" / "⚠ stalled 2 days — check VM login".
- "Making stickers: ✅ 33 published today" / "⚠ worker not running — turn on the schedule".
- "Today's AI budget: 31/60 used."
No logs, no SQL — just green/amber with the one action to fix amber.

## Acceptance criteria
- The owner can tell in 5 seconds whether collection and generation are healthy.
- A stall (collection or worker) raises a visible warning within a day, with the exact fix.
- No stage can silently no-op without it showing up in the health verdict.
