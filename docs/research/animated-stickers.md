# Animated stickers — real motion vs the current "bounce"

## Problem (owner)
"The animated stickers are basically just a bounce-up; I want real animated / video stickers."

## How it works today
`pipeline/create.py` has **two** animated paths:
1. **Real motion** — `_clip()` + `imaging/clip.py` decode a GIF / animated-WebP / MP4 / WebM **source** into frames
   and make a genuinely animated sticker. Triggered when `is_animated_source()` (source mime is video, or frames > 1).
2. **Synthetic motion** — `_motion()` + `imaging/motion.py` take a **static** sticker and apply a canned effect
   (`pop`, `shake`, `zoom_punch`, `typewriter`). This is the "bounce" — frame 0 is the full still, then it scales/
   shakes. Every static sticker also gets one of these as an extra animated variant.

So the bounce appears because: (a) the memes collected so far are **stills** (Pinterest images), so path 1 never fires,
and (b) we auto-emit a synthetic-animated **copy** of each static, which also inflates duplicates (see
`deduplication.md`).

## WhatsApp animated constraints (third-party sources; no official doc found)
- Animated **WebP**, 512×512, **≤ 500 KB**, each frame ≥ 8 ms, total ≤ 10 s, first frame complete. (Our encoder
  already enforces these — confirmed solid in the worker review.)
- GIF is not the delivery format; convert GIF/video → animated WebP, then lower fps/quality until it fits 500 KB. One
  real-world data point: fps ~18, quality ~60. ffmpeg is the usual tool to "whittle quality to reach 500 KB".

## Gaps
1. **Too few real animated sources.** We added GIF collection to HandNavigate, but the GIF topics aren't the ones being
   collected, so almost everything is a still → only synthetic motion.
2. **Synthetic bounce is low value and duplicative** — it makes a near-identical animated twin of every static (the two
   "Shanivaar" packs the owner saw = static pack + animated-bounce pack of the same content).
3. **Long animations get coarse** — converted clips come out ~5 fps (30 frames × 198 ms) to fit 500 KB (BACKLOG item 8b:
   use changed-rectangle ANMF deltas to hold 2–3× more frames in the same budget).
4. **No trimming UX for the best 3 s** of a long clip on the server side (the app's video creator has chips; the worker
   just takes the first seconds).

## Resolution path (easiest first)
1. **Collect real motion.** Verify + prioritise GIF topics (reaction_gifs, funny_gifs, movie_gifs) and add
   **Giphy/Tenor** API sources (free keys, clean mark-free GIFs, India/Bollywood reaction search) — the cheapest way to
   get genuinely animated, good content. Ties to HANDNAVIGATE_PLAN.md.
2. **Stop auto-twinning statics.** Don't emit a synthetic-animated copy of every static by default; only animate when it
   adds something (e.g. a strong caption → `typewriter`), so Discover isn't full of bounce duplicates.
3. **Better synthetic motion** when we do use it: subtle, tasteful presets (gentle float, caption reveal) over
   pop/shake; pick by content, not randomly.
4. **Smoother long clips (BACKLOG 8b):** encode frames 2+ as changed-rectangle ANMF deltas (blend off, even offsets) so
   the 500 KB budget holds more frames; keep full frames when most pixels change.
5. **Smart trim:** pick the most active ~3 s window of a clip (scene/motion scoring) instead of the head.
6. **Quality ladder:** resize→512, convert to animated WebP, then step fps (18→15→12) and quality (70→60→50) until
   ≤500 KB — deterministic, matches the research.

## Open questions
- Giphy/Tenor terms for redistribution as WhatsApp stickers (attribution/content policy) — check before relying on them.
- Do we keep both a static and an animated version of the same meme, or pick one? (UX: users may want the still for
  quick send and the animated for fun — but don't show both as separate Discover packs.)

## Sources
- Animated WebP limits + GIF→WebP + ffmpeg "whittle to 500 KB": https://docs.zavu.dev/guides/whatsapp/messages/sticker · https://github.com/deven96/whatsticker · https://replicate.com/atonamy/images-to-webp-m/readme
- Canva (GIF is not the final sticker format): https://www.canva.com/it_it/strumenti/sticker-animati-whatsapp/
- Lottie→sticker reference: https://github.com/ed-asriyan/lottie-converter
