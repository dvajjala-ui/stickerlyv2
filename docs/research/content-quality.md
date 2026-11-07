# Content & caption quality

## Problem (seen on the first live batch, 7 Nov)
Real memes came through, but the captions and framing had clear defects:
- **Typos / over-stretched spelling:** "anounce", "hotii", "sojaati", "diyaaa" (exaggerated vowels).
- **Same caption on many unrelated images:** "Shanivaar raati hume neend nahi aati" pasted on a cat, a joker, a doge —
  caption didn't fit the picture.
- **Baked-in creator handle / watermark:** "@theangryop" rendered into the sticker.
- **A profanity leak:** "tum chutia hoti" published to a general pack (lexicon fixed; held).
- **Static + synthetic-animated twins** of the same meme (dup noise; see research/animated-stickers.md).

## How it works today
`pipeline/analyze.py` (Groq vision) returns per-image tags/crop/caption_ideas; `pipeline/captions.py` + `ai/prompts.py`
generate captions; `ai/groq_ai.py` runs them; `policy.py` screens; `imaging/compose.py` burns the caption onto the
sticker. Prompts already ask for Hinglish, ≤7 words, match the mood. The gaps are in *consistency and fit*, not the idea.

## Root causes
1. **Caption not tied tightly to the specific image.** The model sometimes reuses a strong line across images because the
   prompt doesn't force "describe THIS image, then caption THIS image" and doesn't pass enough per-image grounding.
2. **No spelling/lint pass.** Exaggerated vowels and typos pass straight through to the burn-in; nothing normalizes
   "anounce"→"announce" or caps repeated letters.
3. **Text already on the image is kept.** When a meme has its own text (handle/watermark/original caption), we don't
   detect "this already has text, don't add a conflicting one / strip the handle" (ties to watermark-handling.md).
4. **Low caption diversity within a pack.** Compose groups by theme; if several stickers got similar lines, the pack
   reads repetitive.
5. **Temperature/variants:** one caption is taken; no A/B pick for best fit.

## Resolution path (easiest first)
1. **Spelling/vowel lint (cheap, deterministic).** A post-process on every caption before burn-in: collapse 3+ repeated
   letters ("diyaaa"→"diya"), fix a small Hinglish misspelling map ("anounce"→"announce"), trim stray punctuation, drop
   leading "@handles". No model change. Add tests.
2. **Strip handles/watermark text.** Using the OCR boxes we already compute, remove "@name"/URL tokens from both the
   burned caption and (where possible) the image region (watermark-handling.md #1). Never render an "@handle".
3. **Ground the caption to the image.** Prompt change: require the model to first output a one-line literal description,
   then a caption that references something specific in it; pass the vision `description` + tags into the caption call so
   it can't drift to a generic line. Add a cheap check: if the caption has near-zero token overlap with the image's
   tags/description, regenerate once.
4. **"Direct" when the meme already has text.** If analysis says `has_text` and the text is a real caption (not a
   handle), prefer the `direct` recipe (keep the meme as-is) instead of adding a second conflicting caption.
5. **A/B pick within budget.** Generate 2 captions, keep the one the caption-judge/quality scores higher (BACKLOG already
   lists caption variants). Cheap with the text model.
6. **Diversity guard in compose.** Don't put two stickers with near-identical captions in the same pack; vary lines
   across a pack.
7. **Raise the caption bar in the quality gate.** Treat "caption doesn't fit / is a handle / is misspelled" as a hold
   reason, so weak ones go to admin review instead of Discover.
8. **Better pack names.** "Shanivaar Chill" vs "Shanivaar Night" (static vs animated of the same content) confused the
   owner — name packs by content/mood, and don't publish a near-twin pack that's only the animated variant.

## What "good" looks like (acceptance)
- Captions are correctly spelled, ≤7 words, Hinglish, and reference the specific image.
- No "@handle", URL, or watermark text is ever rendered.
- No two stickers in a pack share the same caption; packs aren't near-duplicates of each other.
- Weak/odd captions are held for review, not auto-published.
- Measured: spot-check N stickers/day; track hold-rate and admin-reject reasons to tune the prompt.

## Notes
- All of this is worker-side and pytest-verifiable (prompt builders, the lint pass, policy routing) — no app/security
  changes. The lint pass + handle-strip are the fastest wins and fix most of what the owner saw.
