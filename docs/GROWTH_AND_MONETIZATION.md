# Growth, positioning & monetization

Product-owner view: who buys/uses this, how they find us, why they stay, and how (if ever) it earns — without betraying
the "free, no ads, no watermarks" wedge. Planning only. Pairs with PRODUCT_REVIEW.md and UX_RESEARCH_AND_GAPS.md.

## Market reality
- WhatsApp is near-universal in India; stickers are a core way people talk. The category leader is **Sticker.ly** (huge
  library + maker) but it's ad-heavy, watermarked, English-skewed, and has had privacy criticism. Dozens of thin
  "WAStickerApps" fill the long tail with stale, low-quality packs.
- **Our wedge:** India-first taste (Hinglish/Bollywood, actually funny, fresh daily) + **no ads, no watermarks** +
  **one library across both your WhatsApps** (nobody does the multi-account part well). Taste + trust + multi-account.

## Who it's for (segments, priority order)
1. **Everyday Hinglish chatter** (teens–30s, group chats) — core; wants today's mood sticker, one tap to send.
2. **Two-numbers person** (personal + Business/Dual) — our sharpest, least-contested wedge.
3. **Meme-forward sharer** — evangelist if the content is genuinely funny; drives word-of-mouth.
4. (Later) **Creator** — makes/【shares packs; needs moderation first.

## Acquisition (how they find us)
- **Play Store is the #1 unlock.** Sideloading an APK scares non-technical users and kills the funnel. An internal-test
  → open-test → production track is the single biggest growth step. The privacy page is ready (`site/privacy.html`).
- **Built-in share loops (cheap, organic):**
  - *Shareable pack links* (storefront `?pack=` exists; add a `stickerdock://pack/<id>` deep link into the app). A user
    shares a pack → the link brings the next user straight to it.
  - *Every sent sticker is an ad.* A subtle, tasteful credit (not a watermark) or a one-time "made with StickerDock" only
    where it doesn't cheapen the sticker — decide carefully; our wedge is *no* watermarks, so lean toward none.
  - *Keyboard virality:* once a friend sees you firing off perfect reaction stickers in a group, they ask what app.
- **SEO/content:** the storefront is indexable; pack pages for "Monday meme stickers", "Bollywood reaction stickers",
  festival packs (Diwali/Holi) ride seasonal search. Add a sitemap + per-pack pages when a domain exists.
- **Seasonal drops:** festival/cricket/exam-season packs timed to the calendar (ties to the worker's trends stage).

## Retention (why they come back)
- **Fresh daily** is the hook — but it must be *visible*: a "New today" row + an optional daily-drop notification
  ("Monday-mood pack is live"). Without surfacing freshness, the hook is wasted.
- **Recents + favourites** in the send surface → the app becomes muscle memory.
- **Personalised Discover** later ("more like the packs you added").
- **The vault + both-WhatsApps** are retention moats: once your library and both accounts live here, you don't leave.

## Monetization (keep it clean; owner is budget-conscious)
- **Stay free, no ads, no watermarks** — that *is* the marketing against Sticker.ly. Don't trade the wedge for pennies.
- If/when it earns, the clean options (never gate the core send flow):
  1. **StickerDock+ (one-time or small annual):** bigger vault, early access to daily drops, original AI-art packs,
     advanced creator tools. Cosmetic/power, not core.
  2. **Original AI-art packs** (Cloudflare FLUX) as premium themed packs — original art sidesteps rights issues.
  3. **Brand/creator packs** (opt-in, clearly labelled) — a brand or creator sponsors a pack; only if it stays tasteful.
- **Never:** ads in the send path, selling user data, dark patterns, paywalling adding to WhatsApp. Reputation is the moat.

## Cost model (stay near-zero while proving it)
- Supabase free, GitHub Actions free, Oracle VM free, Groq free tier. The only likely spend: Groq paid (~$10/mo for
  ~100 images/day) and Cloudflare FLUX (optional). Keep the free rails until retention is proven.

## Milestones (suggested)
- **M0 (now):** real content live; fix the car-wash bugs; deploy admin+storefront.
- **M1 — "sendable":** send-first flow + hide technical fields + onboarding → genuinely usable by anyone.
- **M2 — "shippable":** Play Store internal test; shareable pack links; "new today" + notification.
- **M3 — "sticky":** recents/favourites, personalised Discover, both-WhatsApps one-tap, quality/dedup polish.
- **M4 — "earns" (optional):** StickerDock+, original-art packs, measured by retention not installs.

## What to measure (privacy-respecting)
Installs → keyboard-enabled rate → stickers sent/user/week (the north star) → D1/D7/D30 retention → packs added →
shares. Aggregate, on our backend, no cross-app tracking. Vanity (installs) matters less than **sends/user/week**.
