# StickerDock content strategy

What we collect, what we make from it, and the voice. Kept current as topics and rules change.
Owner direction (6–7 Nov 2026): India/Hinglish-first, actually funny, memes + adult **humour** (not porn),
movies/series/songs, relationship/bro-code/love, and animated GIFs. AI output goes live to users without manual
confirmation (`publish_policy.auto_publish = true`); the quality gate and the safety policy are the only filters.

## The pipeline in one line
HandNavigate (VM, daily) → Supabase `collected_images` + private bucket → worker `library` copies them to
`source_images` → `prescreen` ranks → `analyze` (Groq vision) tags/crops/rates → `create` cuts out + captions →
quality gate holds weak ones → `compose` builds packs → **Discover** in the app → user library → WhatsApp.

## Collection topics (HandNavigate `collection_topics`)
Each topic has a Pinterest search URL and a top-of-month subreddit. A topic is only collected after a **signed-in
run on that exact URL is imported** (earned verification), so a wrong subreddit name simply never earns and is
skipped — it never breaks a run.

| Group | Topics (keys) | Lane |
| --- | --- | --- |
| General (seeded, verified on Pinterest) | animals, architecture, art, food, nature, travel | general |
| Core memes | memes, indian_memes, hinglish_memes, reaction_memes, bollywood_memes, funny_animals, dank_memes, funny | general |
| Adult **humour** (18+) | dark_memes, adult_humor, dirty_jokes | adult |
| Screen & music | series_memes, movie_memes, old_movie_memes, song_memes | general |
| People & relationships | girls_memes, boys_memes, bro_code, love_memes | general |
| Animated | reaction_gifs, funny_gifs, movie_gifs | general/animated |

Add a topic by adding a row (new migration in `hand/windows-agent/migrations`), then earning it on the VM. Keys are
lowercase; the worker turns `topic_key` into a `topic_hint` by replacing `_` with spaces, which seeds the AI's context.

## Sources, in order of preference
1. **Reddit top-of-month** — best for fresh, genuinely funny, community-voted memes and reaction GIFs.
2. **Pinterest search/boards** — wide coverage, good for Bollywood/old-movie/Hinglish; many are 236 px previews, so
   the collector already prefers the largest `srcset` rendition and (for GIFs) the animated original.
3. **Owner uploads** — the admin **Sources** page: drop files from this PC straight into the DB. These skip
   collection and go into the same prescreen → analyze → create flow.

Not used: scraping locked/private endpoints, rehosting a third party's full catalog, or any paid source without
approval. A download is not a licence; `rights_status` travels with each image and Discover carries a takedown flow.

## What makes a good sticker (the generator's job)
- **Reaction-first.** The best stickers are a face/pose + 1–4 word punchline people actually send in chat: *kya baat
  hai, scene kya hai, bas kar, chal nikal, haan bhai, mood off, ded, savage, oye hoye*.
- **Hinglish > English > Hindi.** Romanised Hinglish lands hardest in WhatsApp. Keep captions short; the image
  carries the joke.
- **Local and current.** Chai/office/exam/monday/salary/traffic/shaadi/cricket/festival beats generic stock humour.
  Trends come from the `trends` stage; evergreen topics fill the rest.
- **Clean cut-out, readable caption.** Subject cut from the background, caption in Anton (Latin) / Noto Sans
  Devanagari, 512 px, under the WhatsApp size limits. The gate rejects blurry, tiny or unreadable results.

## Caption voice
- Punchy, spoken, a little savage; never a full sentence when three words do.
- Hinglish spellings people type (*nahi, kya, bhai, yaar, scene, bakwaas*), not textbook Hindi.
- Emoji sparingly (0–1), only if it adds.
- **Adult lane = adult humour**, double-meaning/flirty/savage/dark on clothed, non-explicit images. Never sexual
  captions on real identifiable people; never anything adult near a minor.

## Hard safety rules (not adjustable — `stickerworker/policy.py`)
- No sexually explicit images or captions. No nudity-as-sticker. "Porn" is not a content lane and is not collected.
- Nothing involving minors is ever adult, and never carries adult text.
- No hate or slurs. No real-world threats or gore.
- Protected public figures (Delhi/Bombay HC personality-rights orders — Bachchans, Anil Kapoor, Jackie Shroff,
  Arijit Singh, etc.) are handled per `publish_policy.celebrities`; the default is cautious.
- Adult stickers go to the adult lane only when `publish_policy.adult_lane = true`.

## Settings that shape output (the `settings` table / admin → Settings)
- `publish_policy.auto_publish = true` — good stickers publish straight to Discover, no manual step.
- `publish_policy.min_quality`, `quality_gate.min_score` — raise to be pickier, lower for more volume.
- `budgets.vision_per_day` (60), `text_per_day` (400), `max_new_stickers_per_day` (120) — the daily AI ceiling; with
  one Groq key paced at ~24/min this is comfortable. Raise once we confirm quota headroom.
- `categories.enabled` — which categories the generator may use (reactions, funny, hinglish, movies, old_movies,
  songs, memes, dark, relationship, office, festivals, animals, baby, trending, adult).

## Daily volume target
~100 images/day collected, prescreen keeps the top slice within the vision budget, aim for 40–80 published
stickers/day grouped into a few packs. Watch the first 7 days for duplicates, login challenges and flops before
trusting the number.
