# StickerDock — product review from the user's side

Not "did we build the spec" but "would a real person pick this, and keep it?" Written as product owner,
7 Nov 2026. Grounded in what the code actually does today; aspirations are marked as such.

## Who actually uses this
1. **The everyday Indian WhatsApp chatter (our core).** Teens–30s, texts in Hinglish, lives in group chats, sends
   10+ stickers a day. Wants stickers that match *today's* mood and slang. Currently uses Sticker.ly or random
   WAStickerApps full of low-quality, watermarked, English-only packs.
2. **The two-numbers person (the owner's own itch).** Personal + Business WhatsApp (or a cloned/Dual-Messenger
   WhatsApp). Hates maintaining stickers twice. **Nobody else solves this well** — this is our sharpest wedge.
3. **The meme-forward sharer.** Wants dank/dark/adult-humour and Bollywood reaction packs, fresh, without trawling
   Instagram. Will evangelise if the content is genuinely funny.
4. **Later: the creator.** Wants to make and share their own packs. Out of scope until moderation exists.

## The job they hire us for
> "Give me the perfect sticker to drop in this chat, right now, in my language — and make it one tap to send,
> on all my WhatsApps."

Everything should be judged against that sentence. Content relevance + speed-to-send is the whole game.

## The competition, honestly
| App | Does well | Where we win |
| --- | --- | --- |
| **Sticker.ly** (market leader, IN) | Huge catalog, search, maker, social | Quality is noisy/watermarked, English-skewed, ad-heavy; **no multi-account**; privacy concerns |
| **WAStickerApps (dozens)** | Free, niche packs | Stale, low quality, spammy, no curation |
| **Bitmoji / Memoji** | Personal avatar | Not meme/reaction, not Hinglish, not shareable culture |
| **WhatsApp native stickers/AI** | Built in, zero install | Generic, not India-tuned, no library management, no cross-account |

**Our one-line positioning:** *The India-first sticker app that keeps one tasteful, always-fresh Hinglish library
across both your WhatsApps.* Content taste + multi-account + privacy, against their scale + noise.

## The journey today, with friction flagged
1. **Find out we exist** → website storefront (`site/`). ✅ now has a hero + value prop. ⚠️ needs real content to
   convince; an empty catalog kills trust. **Blocker: meme collection.**
2. **Get the app** → APK. ⚠️ sideloading an APK is a big drop-off vs Play Store. *User-centric fix: a Play Store
   listing is the single biggest growth unlock; sideload scares non-technical users.*
3. **First open** → see Discover. ✅ exists. ⚠️ first impression must be "oh these are funny AND in my language"
   within 5 seconds — so the first packs shown must be the best, India-tuned ones (ranking matters).
4. **Add a pack** → tap → Add to WhatsApp (personal + Business). ✅ core works. ⚠️ the number of taps and the
   on-WhatsApp confirmation dialog are the moment of truth; must be frictionless and obviously work for *both*
   accounts.
5. **Send in chat** → keyboard or share. ✅ sticker keyboard exists. ⚠️ in-chat search from the keyboard is the
   killer habit-former; must be fast and find by Hinglish word/emoji.
6. **Come back tomorrow** → why? ✅ "fresh daily" is the hook. ⚠️ needs a reason to return: a "new today" badge, a
   notification ("Monday-mood pack is live"), favourites, trending.

## What makes them choose us (and stay) — ranked
1. **Content that's actually funny and actually Hinglish.** Non-negotiable #1. Everything else is plumbing. This is
   why generic travel/food photos (current library) get correctly rejected — we must collect memes.
2. **One tap to both WhatsApps.** The differentiator nobody copies. Make it loud in the UI and the store listing.
3. **Fresh every day.** A reason to open daily. Needs a visible "new today" surface.
4. **Fast in-chat send** via the keyboard with Hinglish search.
5. **Private vault + no ads + no watermarks.** Trust and taste vs the spammy field.
6. **It just works offline and keeps your library safe** (backup/restore). Peace of mind.

## Gaps that matter to users (not just to the spec)
Highest user impact first:
1. **Real, funny content** (collection on the VM). Without it, nothing else matters. — *blocked on VM access.*
2. **Play Store presence** / easier install. Biggest growth lever. — *owner decision + Play account.*
3. **"New today" / trending surface** in app + site, with a return reason. — *buildable.*
4. **In-app one-tap "add to both WhatsApps"** made obvious, with clear success feedback. — *polish existing.*
5. **Keyboard Hinglish search** that's instant and forgiving of spelling. — *tune existing.*
6. **Manual cutout touch-up (eraser)** for user-made stickers — auto-cutout misses edges. — *app work.*
7. **Shareable pack links** ("send this pack to a friend") — organic growth loop. — *server + app.*
8. **Report button** in Discover — trust + safety. — *server exists; wire the button.*
9. **Personalization** — "packs like the ones you added," language preference. — *later.*

## Monetisation (owner is budget-conscious; keep it clean)
- **Free, no ads, no watermarks** as the wedge — that *is* the marketing against Sticker.ly's ad load.
- Later, if ever: optional one-time "StickerDock+" (bigger vault, early access to daily drops, original AI art
  packs). Never gate the core send flow. Never dark-pattern. Reputation is the moat.

## Roadmap, tied to user value
**Now (this week, mostly backend/web — what we can ship without the phone):**
- ✅ Pipeline hardened (JSON fallback, self-healing leases), one-key Groq rotation, storefront.
- Collect the meme/GIF topics on the VM → real content → the whole thing comes alive. *(needs VM access)*
- "New today" ordering on the site + a published-date badge.
- Admin "Collect" page so topics/runs are managed from the UI.

**Next (phone + polish):**
- Play Store internal-testing track (reduce install friction).
- In-app: obvious "Add to both WhatsApps," success toast, "new today" row, favourites front-and-centre.
- Keyboard: faster Hinglish/emoji search.
- Shareable pack links; in-app report button.

**Later (growth + depth):**
- Personalised Discover; notifications for daily drops.
- Manual cutout eraser; simple caption editor; original AI-art packs (Cloudflare FLUX).
- Creator uploads with moderation/identity/age-gating (its own project).

## The one sentence to remember
If opening StickerDock doesn't make someone laugh and reach for a sticker within five seconds, nothing else we build
matters. Content taste first; everything else serves the send.
