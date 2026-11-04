# StickerDock v2 — product

## Promise
**One sticker library for every WhatsApp you use, always in sync — plus fresh AI-made reaction stickers every day.**

## Who it is for
People in India with more than one WhatsApp on a phone (personal, Business, Samsung Dual Messenger clone, WhatsApp's
in-app second account) who send stickers all day, mostly Hinglish reactions, and are tired of re-adding packs, losing
stickers, ads and paywalls (the top complaints about Sticker.ly in its reviews).

## Core feature 1 — WhatsApp everywhere
| Need | How v2 does it |
| --- | --- |
| Add to WhatsApp and Business fast | Pack screen shows one **Add** button per installed WhatsApp with live "Added ✓" status (WhatsApp's whitelist check). One tap + WhatsApp's own confirm per app. |
| Keep every account in sync | Packs are served live from the app (WhatsApp's provider contract); every edit bumps `image_data_version`, so WhatsApp reloads by itself. |
| Dual Messenger / clone WhatsApp | Android forbids other users reading our provider, so a one-time setup installs a StickerDock **companion** inside the clone profile, linked to the main library (cross-user sync provider). Adds to the clone WhatsApp from the main app via `CrossProfileApps`. |
| WhatsApp's in-app second account | Packs add to the active account only (WhatsApp limitation): switch account, tap Add again. The **StickerDock keyboard** works in any account with no adding at all. |
| Send any sticker, anywhere | **StickerDock keyboard** (IME): whole library, search by what you just typed, recents/favourites/packs; commits WhatsApp's sticker type (`image/webp.wasticker`) so it arrives as a real sticker. |
| Manage everything | Sticker-first library: CRUD stickers and packs, multi-select, favourites, tags/emojis, global search, undo delete, static/animated kept separate, 3–30 per pack enforced. |
| Bring stickers in | Phone's WhatsApp Stickers folder (keeps original pack names from EXIF), ZIP/.wastickers, StickerDock v1 & v2 backups, photos, share-sheet. Resumable background import with dedup. |
| Get stickers out | Share a pack as `.wastickers` (Sticker Maker compatible), full library backup ZIP. |

## Core feature 2 — AI sticker factory
- Images arrive daily (owner's HandNavigate collector → `intake` endpoint; admin uploads; free sources).
- **Understand first**: free local pre-screen on every image (pHash dedup, quality, OCR) → budgeted Groq vision for
  the most promising: meme or not, existing text, emotion, situation, tags in English + Hinglish, safety flags.
- **Make stickers** with the right recipe: *direct* (already a meme), *caption* (Hinglish caption on a reaction),
  *cutout*, *motion* (animated variants), *GIF/video* clips; optional *remake* (new original art).
- **Individual stickers are first-class** in Discover (trending feed + search); **packs are built from them**:
  growing series packs per category (new stickers flow into WhatsApp automatically for followers), daily trend drops.
- Categories: trending, reactions, funny, hinglish, movies, old movies, songs, memes, dark, relationship, office,
  festivals, animals, cute/baby, 18+.
- Admin panel (separate web service): sources with searchable AI metadata, sticker/pack review, reports/takedown,
  trends, budgets, policy.

## 18+ lane (owner decision)
Adult humor (double meaning, spicy, savage, dark) with clothed imagery, age-gated, blurred until opted in, separate
Discover lane; private offline **vault** for users' own explicit stickers (never synced, shared or published).
Hard limits in `CONTENT_POLICY.md` are not configurable.

## Not in this release
Accounts/cloud sync across phones (companion covers the same phone), iPhone polish (shared code is ready; needs macOS
CI + Apple account), community uploads, payments.
