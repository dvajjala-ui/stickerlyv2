# StickerDock AI worker (`stickerworker`)

Turns collected images into WhatsApp-ready stickers and packs for the StickerDock v2 app.
Python 3.12; dependencies: httpx, Pillow, imagehash, numpy (optional extras: `ocr`, `cutout`).

```
source_images: new ─prescreen─▶ screened ─analyze─▶ analyzed ─create─▶ used   (stickers rows + files)
                    ├▶ rejected (too small, blank, duplicate)   └▶ rejected (policy)
                    └▶ taken_down (fingerprint match)                    ─compose─▶ packs / pack_items
trends ◀─trends─ Google Trends IN RSS + PIB RSS (titles/keywords/links only) + owner topics
```

## Setup

```powershell
# from backend/worker
D:\5. GitUploaded\sticker\.venv\Scripts\python.exe -m venv .venv
.venv\Scripts\python -m pip install -e ".[dev]"          # add ,ocr or ,cutout as needed
.venv\Scripts\python -m pytest -q                         # no network, no keys
```

Linux/CI: `pip install ".[ocr]"` plus `apt-get install tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin ffmpeg libfribidi0`.
Pillow's wheels contain libraqm and load FriBiDi at runtime. With FriBiDi present, Devanagari
conjuncts shape correctly (`python -c "from PIL import features; print(features.check_feature('raqm'))"`).
Without it (e.g. stock Windows) the worker still renders Devanagari, but `create` drops
Devanagari captions so no sticker ships with broken shaping. Romanized Hinglish is unaffected.

## Offline dry run (no keys, no cloud)

```powershell
.venv\Scripts\python -m stickerworker run --stage all --local C:\path\to\images
# results: out\index.html (gallery), out\report.json, out\local.sqlite3, out\storage\stickers\*.webp
```

`--local DIR` loads the folder into a `LocalStore` (SQLite + files under `backend/worker/out/`, gitignored)
and runs every stage offline. Trends are not fetched in this mode. With no `GROQ_API_KEYS` it uses the deterministic `FakeAI`, which labels
pictures from file-name words. Names such as `meme_text_*`, `baby_*`, `explicit_*`, `amitabh_bachchan_*`,
`adult_nonveg_*`, `cutout_*` and `*_office_*` exercise each path. To generate a demo folder:
`.venv\Scripts\python -c "from pathlib import Path; import sys; sys.path.insert(0,'tests'); from samples import write_sample_folder; write_sample_folder(Path('demo-in'))"`.
With `GROQ_API_KEYS` set, `--local` calls the real models against your folder, still writing only locally.

`--dry-run` (any store) is read-only. Claims become plain reads, writes are recorded in the summary,
uploads are written as previews under `--out/dry-run/`, and FakeAI is used, so no AI budget is spent.

## Commands

| Command | What it does |
|---|---|
| `run --stage all\|trends\|prescreen\|analyze\|create\|compose [--limit N] [--dry-run] [--local DIR] [--out DIR]` | Run stages (`all` runs them in that order; trends are fetched at most every 3 h). Each stage is recorded in `worker_runs`. |
| `intake DIR --site upload [--collector handnavigate] [--topic TEXT] [--dry-run]` | Push a folder into Supabase `source_images` (dedupe by sha256, takedown check). |
| `pending [--github-output]` | Print queue counts; CI uses it to skip installing OCR/ffmpeg when idle. |

Exit codes: `0` ok (also when budgets or rate limits stopped a stage early), `1` a stage crashed (see `worker_runs`), `2` configuration error.

## Environment

| Variable | Default | Notes |
|---|---|---|
| `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | none | Required except with `--local`. Service role key: CI secret / local `.env` only. |
| `GROQ_API_KEYS` | none | Comma-separated. Used for **failover / separate paid orgs only**. Free limits are per organization and Groq's AUP forbids multiplying quota with extra accounts. `GROQ_API_KEY` is also read. |
| `GROQ_VISION_MODEL` | `qwen/qwen3.8-27b` | Preview id, so expect it to rotate. Keep it configurable. |
| `GROQ_TEXT_MODEL` / `GROQ_SMALL_TEXT_MODEL` | `openai/gpt-oss-120b` / `openai/gpt-oss-20b` | Captions / pack titles. |
| `GROQ_SAFETY_MODEL` | `openai/gpt-oss-safeguard-20b` | Caption judge. Set to empty to disable it. Disabled = captions are trusted after the local rules. |
| `GROQ_VISION_BATCH` | `3` | Max images per request (1–3). |
| `GROQ_VISION_TPM` | `8000` | Tokens/minute of your tier. On free tier (8K) batches shrink to 2 images (each image ≈ 2,048 tokens). |
| `GROQ_VISION_REASONING` / `GROQ_TEXT_REASONING` | `none` / `low` | Sent as `reasoning_effort` (empty = omit). |
| `GROQ_MAX_WAIT_SECONDS` | `75` | Longest the client sleeps for a rate-limit reset before ending the stage cleanly. |
| `REMBG_MODEL` | `isnet-general-use` | Only `isnet-general-use` or `birefnet-general` are accepted. rembg's default model is never used. |
| `STICKERWORKER_OUT` | `backend/worker/out` | Output folder for `--local` / `--dry-run`. |

## Budgets and the Groq client

* `settings.budgets.vision_per_day` counts **images** per IST day. Usage = sources analyzed today + vision calls that
  returned HTTP 200 but unusable output. `text_per_day` counts text requests that returned HTTP 200
  (`caption`, `judge`, `pack_title`, `trend`). Usage is read from the ledger at start and tracked in-process.
  `max_new_stickers_per_day` caps `create`.
* Every HTTP attempt is logged to `ai_calls`. Rows hold the key's slot index only (never the key) and a short error code (never the body).
* HTTP 429 → that key cools down for `retry-after` (or the `x-ratelimit-reset-*` value) and the next key is tried. If the
  wait is longer than `GROQ_MAX_WAIT_SECONDS`, the stage stops and unprocessed sources go back to `screened`.
  `x-ratelimit-remaining-tokens/-requests` are honoured *before* sending. 413 shrinks the image batch. 401/403 disables a key.
  A 400 on a strict `json_schema` falls back once to `json_object` with the schema in the prompt.
* All structured calls use strict JSON schema (`ai/schemas.py`). Every property is in `required` and every object has `additionalProperties: false`.

## Stages

1. **prescreen** (free): decode (GIF first frame + frame count, video poster via ffmpeg), sharpness/contrast/aspect,
   64-bit pHash, near-duplicate (Hamming ≤ 8 vs recent sources/stickers), takedown fingerprints (sha256, pHash,
   source_url → `taken_down`), optional OCR (eng+hin), `prescreen_score` 0–10.
2. **analyze** (budgeted vision): highest `prescreen_score` first. The `claim_sources` RPC orders by `collected_at`,
   so this stage claims by score with an optimistic conditional PATCH. Images are downscaled to 1024 px, EXIF stripped,
   and sent as JPEG base64. Output: kind, text, language, category, mood, tags (English + romanized Hindi), ≤3 emojis, nsfw, minors_likely,
   real_person, public_figure, violence_or_gore (`none|mild|graphic`), hate_or_slur, sticker_score, best_recipe, crop,
   caption ideas. Safety fields are mandatory; malformed answers are retried, never guessed.
3. **create**: `policy.screen_source`, then recipes:
   * *direct*: a meme with text, cropped to subject + text (crops that would cut text are ignored, flat borders trimmed). No overlay.
   * *caption*: the text model writes captions (strict JSON: text, language, style, rating, emojis, trend) from the analysis and active trends.
     They pass the local rules, then the safety judge. Text is white with a black outline ≥ 6 px, auto-wrapped and auto-sized,
     in Anton or Noto Sans Devanagari. It goes below wide pictures, or top/bottom on whichever band covers less of the subject.
     A picture that already has text is never captioned.
   * *cutout* (extra): rembg subject + 8 px white outline + caption.
   * *motion*: pop / shake / zoom-punch / typewriter variants of a strong static sticker.
   * *clip*: GIF/MP4/WebM trimmed to ≤ 3 s at 10 fps.
   Every output is checked by `imaging/validate.py`: 512×512 WebP, static ≤ 100 KB, animated ≤ 500 KB, frames ≥ 8 ms,
   ≤ 10 s, complete first frame. Then `policy.decide` sets status/rating, and the file is uploaded to
   `stickers/<id>.webp`. Its row carries sha256, the source pHash (so takedowns block re-ingest of the
   picture), tags, emojis, credit `{site,url,author}`, quality and `ai_generated`. `ai_generated` is false only for
   as-found direct/clip stickers.
4. **compose**: series packs per (category, kind, rating) grow to 30, then `Vol N+1` opens. Daily packs are made for active
   trends with ≥ 6 matching stickers. Kind and rating are part of every key, so packs never mix static/animated or
   general/adult. Non-published stickers are pruned, the best-quality sticker becomes the cover, and a 96×96 tray goes to
   `stickers/trays/<pack_id>.png`. Packs publish at ≥ 3 published stickers when `auto_publish` is on. Titles come
   from the small text model, with a deterministic Hinglish fallback (adult titles always start with "18+").
5. **trends**: Google Trends IN (`trending/rss?geo=IN`) and PIB RSS. Only titles, keywords and links are kept, never
   article text or images. Tragedies and protected people are skipped. Fetched trends older than 48 h are deactivated;
   owner topics (`source=owner`) are left to the owner.

## Content policy (`stickerworker/policy.py`, pure + unit-tested)

Hard rejects: explicit nudity/sex, explicit or slur text, hate, graphic violence, protected people (`protected_people`,
fuzzy + court-order aliases such as "jhakaas", "bhidu", "big b"), a real person with suggestive imagery or a
sexual caption, and minors with any nsfw, any adult/dark/relationship category, or any adult text.
Adult rating applies only to adult category, suggestive cartoon art or an 18+ humour caption, never with minors.
Publish policy: published unless `auto_publish` is off, quality < `min_quality`, the site is in
`require_review_sites`, `celebrities` is `review`, or a caption could not be judged. Any of those makes a `draft`.

## How HandNavigate posts images

HandNavigate (desktop) exports a folder of downloads. Each file may have a sidecar `<file>.json` or `<stem>.json`
with `source_url`, `page_url`, `author`, `license`, `topic_hint`, `site`. Then:

```powershell
$env:SUPABASE_URL="https://<project>.supabase.co"; $env:SUPABASE_SERVICE_ROLE_KEY="<from .env, never committed>"
.venv\Scripts\python -m stickerworker intake D:\HandNavigate\exports\2026-11-05 --site pinterest --collector handnavigate
```

Originals go to the private `sources` bucket at `<site>/<sha[:2]>/<sha>.<ext>`. File type comes from magic bytes,
and files that are duplicates (sha256) or match a takedown are skipped. The scheduled worker picks the rows up on its next run.

## CI (`.github/workflows/worker.yml`)

Push/PR touching `backend/worker/**` runs pytest. A schedule (every 30 min) and manual dispatch run the stages.
Concurrency is one at a time. Secrets: `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `GROQ_API_KEYS`. Model ids
can be overridden with repository variables. Without the Supabase secrets the job exits 0 with a notice. Without Groq keys,
analyze is skipped. OCR/ffmpeg are installed only when the queue has media work.

## Layout

`ai/` (Groq client, prompts, schemas, FakeAI) · `imaging/` (decode, metrics, layout, text, compose, motion, clip,
encode, validate, cutout, ocr) · `pipeline/` (stages, runner, captions, recipes, packs, trends, intake) ·
`store/` (protocol, Supabase, Local, DryRun) · `policy.py`, `lexicon.py`, `analysis.py`, `settings.py`, `config.py`.
Fonts (SIL OFL 1.1, licence files alongside), downloaded from `github.com/google/fonts` (`main`, 2026-11-05):

| File | Source path | sha256 |
|---|---|---|
| `Anton-Regular.ttf` | `ofl/anton/Anton-Regular.ttf` | `a4ba3a92350ebb031da0cb47630ac49eb265082ca1bc0450442f4a83ab947cab` |
| `NotoSansDevanagari-Variable.ttf` | `ofl/notosansdevanagari/NotoSansDevanagari[wdth,wght].ttf` (renamed) | `14ec4af41f27482216d1c2229f417ff9b1425e1babb014e57d1d40d03229853e` |
