# Content policy (enforced in `worker/stickerworker/policy.py`, the app and the admin)

## Never publish (not configurable)
1. Sexually explicit material: nudity, sex acts, sexual close-ups. (IT Act s.67A; WhatsApp and Play rules.)
2. Anything sexual or suggestive involving a real, identifiable person.
3. Anything involving a child that is not wholesome; images with a possible minor never get 18+, dark or
   relationship captions.
4. Hate, slurs, caste/religious abuse, harassment of private people, graphic violence or gore.
5. People listed in `protected_people` (standing Indian court orders on their persona, incl. memes/GIFs).
6. Jokes about victims of tragedies; false factual claims about real people (defamation).

## 18+ lane
Allowed: double-meaning/"non-veg" humor, flirty and spicy relationship lines, savage roasts, mild gaali humor, dark
jokes, party/drinking jokes — with clothed imagery. Age gate (self-declared 18+), blurred until opted in, never shown
in the default feed, never mixed into general packs. The private vault holds users' own imports, stays offline.

## Collected images (owner decision: publish as found + takedown)
- Every published sticker carries `credit` (site, URL, author when known) and an **AI-generated** label when AI made
  or captioned it (IT Rules amendment, Feb 2026).
- Anyone can report from the app; admins take down in one click. A takedown stores sha256/pHash/source URL in
  `takedowns`, so the same image is never ingested or published again. Target: act on reports within hours
  (court/government orders: 3 hours; non-consensual intimate imagery: 2 hours).
- `settings.publish_policy` can force review for specific sites or raise the quality bar.

## Captions
Hinglish/Indian context first, short (≤ 8 words ideal), readable at 512 px, never over existing text. No verbatim
song lyrics beyond a short phrase; famous film dialogue lines are fine as short quotes.
