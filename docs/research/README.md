# Research library

Deep-dive research + planning so any AI/engineer can understand *why* the system is built the way it is, what is
broken, and the resolution path — not just a task list. Each file follows the same shape: **Problem → How it works
today → What competitors/research say (with links) → Gaps → Resolution path (easiest first) → Open questions**.

Written 7 Nov 2026. These are planning docs; they change no code.

| File | Covers |
| --- | --- |
| [architecture-and-patterns.md](architecture-and-patterns.md) | the whole system: stack, data flow, the patterns/logic/reasons per component (app, admin, worker, collector), and the top problems→solutions |
| [watermark-handling.md](watermark-handling.md) | detecting & removing watermarks/handles on collected images (open-source tools, pipeline fit, rights caveat) |
| [animated-stickers.md](animated-stickers.md) | real animated stickers vs the current synthetic "bounce"; WhatsApp animated-WebP limits; using GIF/video sources well |
| [deduplication.md](deduplication.md) | the dedupe tool's gaps (app + worker), better near-duplicate detection/clustering, keep-best, review UX |

Related planning already in `docs/`: [UX_RESEARCH_AND_GAPS.md](../UX_RESEARCH_AND_GAPS.md) (send-first flow, competitor
UX), [REVIEW_2026-11-07.md](../REVIEW_2026-11-07.md) (~60 verified code findings), [WORK_QUEUE.md](../WORK_QUEUE.md)
(ordered plan), [AI_HANDOFF.md](../AI_HANDOFF.md) (lineage, how to run, where creds live).

## Method / how to use this
1. Read `architecture-and-patterns.md` to get the mental model.
2. For a feature, read its research file, then turn the "resolution path" into tasks in `WORK_QUEUE.md` with
   acceptance criteria before writing code.
3. Prefer the cheapest resolution that ships value; most items need no new service and no security/key changes.
4. Validate: worker = pytest; storefront = `site/smoke.py` (Playwright); app = `:shared:jvmTest` + device; admin = CI.
