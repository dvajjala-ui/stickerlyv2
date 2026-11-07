# UX research, flow gaps & product plan

Written 7 Nov 2026 after the owner (a non-technical founder) tested the live app and hit real friction. This is a
planning doc — **no code is changed by it.** It separates what is a **WhatsApp platform limit** from **our own
choices we can fix**, researches how competitors handle the same problems, and lays out the optimal flows and a
prioritized plan. Another AI/engineer should be able to pick up any item here and build it.

Guiding principle (owner's words): the app must be usable by *"a 6th-standard kid who can navigate and use it fast."*
Users are not developers. They never see code, internal names, SHAs, statuses or "recipes". They open the app, find a
sticker, and send it — or add a pack — in the fewest taps. Everything technical happens in the backend.

---

## 1. What is a WhatsApp platform limit vs. our choice

| Rule the owner hit | Whose rule | Can we change it? | What we do instead |
| --- | --- | --- | --- |
| A sticker pack added to the WhatsApp tray must have **3–30 stickers** | **WhatsApp's** (the WAStickerApps ContentProvider contract) [1][2] | No — WhatsApp rejects a <3 pack | Never make the user hit it: packs are pre-built 3+; auto-pad the user's own pack; and offer **one-tap send of a single sticker** which does NOT need a pack (see §3) |
| A pack already added to WhatsApp **can't be edited**; you make a new pack | **WhatsApp's** [2] | No | Our "followed packs" model updates by versioning; for the user it looks like the pack refreshes |
| Discover → tap sticker → go to Stickers → add to a pack → then WhatsApp (long chain) | **Ours** | **Yes — fix it** | Collapse to one action (see §3). This is the #1 complaint. |
| Can't send just one sticker | Half ours | **Partly** | WhatsApp can't *tray* a 1-sticker pack, but it CAN *receive* a single sticker via share/keyboard. Surface "Send now". |
| User sees sticker file names / code / internal labels | **Ours** | **Yes — fix it** | Hide every internal field; show only the image (see §5) |
| Soft-delete only | **Ours** | **Yes — add hard delete** (see §6) | |

Sources: [1] WhatsApp Android sticker integration guides (3–30 per pack, 512×512, <100 KB). [2] Multiple how-to guides
note a pack can't be edited after it's added. See the Sources list at the bottom.

## 2. How competitors solve the "find → send" problem (research)

- **Telegram** (the gold standard for sticker UX): stickers are *instant to send* — tap the sticker tray in the
  keyboard, tap a sticker, it sends. No "packs", no "add", no export step. Discovery and sending are the *same*
  surface. This is the bar.
- **Sticker.ly** (market leader in India): make/choose → **Save to a pack → "Add to WhatsApp"**. It still forces the
  pack step, and once added you can't edit the pack [2]. Its win is a huge searchable library and a one-button add;
  its loss is ads, watermarks, and the same multi-step chore we have.
- **WhatsApp's own built-in stickers**: search bar in the sticker tray; recently-used row; AI-create. The lesson:
  **search + recents right inside the send surface**, zero navigation.
- **Bitmoji/Memoji**: one-tap send from the keyboard; no packs at all.

**Takeaway:** the winning pattern is *send-first, not pack-first*. Packs are a WhatsApp distribution detail, not the
user's mental model. Our keyboard already sends directly — we've buried it.

## 3. The core flow fix — "send-first"

Today (what the owner hit): Discover → tap sticker → Stickers tab → add to a pack → create/choose pack (needs 3) →
WhatsApp tab → add pack → open WhatsApp. ~7 steps, developer-shaped.

**Proposed (target):**
1. **One primary action on every sticker, everywhere: "Send".** Tapping a sticker (in Discover, search, or library)
   opens the system share / our keyboard and drops it straight into a chat. No pack, no "add", works for a **single**
   sticker. This is the fast path users actually want.
2. **Secondary action: "Save".** One tap saves the sticker to the user's library (an auto "Saved" pack). No naming,
   no pack picker.
3. **"Add pack to WhatsApp"** stays for whole packs, but becomes a background nicety, not the only route. When a
   user saves stickers, we auto-maintain a ready "My Stickers" tray pack (padded to 3 with their own saved ones) so
   the WhatsApp tray always has their stuff — they never manage it.
4. **The sticker keyboard becomes the hero**: onboarding should push "enable the StickerDock keyboard" so users send
   from inside any chat with search + recents, Telegram-style. (We already built the keyboard; it's under-surfaced.)

Net: find a sticker → **one tap to send**. Packs/export become invisible plumbing.

## 4. "2 stickers per pack" — the honest answer + the workaround
WhatsApp will not add a pack with fewer than 3 to its tray [1]. So we can't literally export a 2-sticker tray pack.
But we can honour the intent: (a) let users **send** 1–2 stickers instantly (no pack), and (b) when they want them in
the WhatsApp tray, **auto-pad** the pack to 3 from their recent/favourite/saved stickers (or a tiny house sticker),
and tell them plainly "WhatsApp needs at least 3 in a tray — we added your recents." No dead-end error.

## 5. Hide everything technical from users
Audit and remove from all user-facing screens (app Discover/library, storefront): file names, SHAs, sticker "label"
when it's a filename, "recipe", status words (draft/held/published), category slugs, pack "series" keys, "collector",
topic keys. Users see: the image, a short human caption (optional), and Send/Save. The storefront already hides most;
the app library still shows import file names in places (A13/A-nits in REVIEW_2026-11-07). Make a single rule: the UI
renders only image + optional human caption + the 2 actions.

## 6. Hard delete (owner request)
Today delete is soft (kept 7 days for undo) and import can *undelete* (app review A6/A7). Plan:
- Keep a short undo window, but add an explicit **"Delete permanently"** in the library multi-select and the
  duplicate/cleanup screen that purges rows + files immediately and records the SHA so re-import can't revive it.
- Import must **never** resurrect a hard-deleted sticker (store deleted SHAs; skip them on import).
- Admin: a hard-delete for sources/stickers (not just reject), with the file removed from storage.

## 7. Dual Messenger / WhatsApp Business (owner request)
Owner wants us to *detect* a cloned/Business WhatsApp and make adding stickers there as easy as the main one — ideally
auto, maybe via a tiny companion.
- **Detect**: enumerate installed WhatsApp packages (`com.whatsapp`, `com.whatsapp.w4b`) and cloned-app user profiles
  (Samsung Dual Messenger / Island / work profile). We already have `WhatsAppDirectory` + a Dual companion; the gap is
  it's manual and dev-shaped (adb). Plan a **one-tap "set up second WhatsApp"** that installs/enables the companion
  into the clone profile and grants it, with a plain guide — no adb for the user.
- **"Add to all my WhatsApps"** exists but stops after the first Dual target (A24). Fix so one tap fans out to every
  detected WhatsApp.
- The "separate app to sync the cloned account" the owner suggested = the companion; make it a real, installable
  one-tap flow, not developer commands.

## 8. HandNavigate content quality & quantity (owner: "very few sources, content not good")
- **Quantity**: today one random meme topic/day × 2 jobs = ~40 images. Plan: more daily jobs, more verified topics
  (earn them on the VM), and add API-friendly sources (Reddit top, Imgflip, Giphy/Tenor for GIFs) so volume isn't one
  Pinterest board. See HANDNAVIGATE_PLAN.md + ORACLE_VM.md.
- **Quality**: the first live batch showed repeated/mismatched captions, typos ("hotii", stretched vowels), a baked-in
  watermark handle, and a profanity leak (now fixed). Plan: tighten the caption prompt (fit caption to the image,
  clean spelling, strip @handles/watermarks), add a caption A/B pick, and raise the quality gate for captions.
- **Hard delete bad sources** in admin so junk doesn't linger (see §6).

## 9. Other gaps I found (not yet raised)
- **No onboarding.** First open should teach the 3 things that matter: enable the keyboard, how to send, how to add to
  both WhatsApps. A "6th-standard" user needs a 20-second guided first run.
- **Empty/loading states** flash raw text ("Bring your stickers home" before data loads) — polish.
- **No "recently sent" / favourites row** in the send surface (the single biggest speed win after search).
- **Search must be instant and forgiving** (Hinglish spelling, emoji) — it exists but isn't the front door.
- **No share-a-pack link into the app** (storefront has `?pack=`; the app has no deep link to open it). Growth loop.
- **Discover shows packs but "independent stickers" the owner wants** — add a flat "all stickers" view + search that
  sends on tap (ties to §3).
- **Pack minimum friction surfaces as errors** instead of being handled silently (admin W3 fixed; app side pending).
- **Accessibility / one-handed use**: big tap targets, bottom-reachable primary actions.

## 10. Prioritized plan (impact first; all are app/UX, not security)
**P0 — the send-first flow (§3).** Biggest retention lever. Tap-to-send single sticker + save, keyboard as hero.
**P1 — hide all technical fields (§5)** and **onboarding (§9).** Makes it "6th-standard" usable.
**P2 — one-tap "add to all WhatsApps" incl. Business/Dual, as a guided flow (§7).**
**P3 — hard delete end to end (§6).**
**P4 — content quality + quantity (§8):** caption prompt, more sources/jobs, admin hard-delete of junk.
**P5 — recents/favourites row + instant search in the send surface (§9).**
**P6 — share-a-pack deep link into the app (§9).**

Each P-item should become a worked task in WORK_QUEUE.md with acceptance criteria before building. None requires
touching security/keys.

## Sources
- WhatsApp Android sticker pack integration (3–30 per pack, 512×512, <100 KB): https://github.com/fahimvalanchery/whatsapp-sticker-android ; https://www.digit.in/how-to/mobile-phones/create-your-own-whatsapp-sticker-apps-on-android-45267.html
- Pack can't be edited after adding; add-to-WhatsApp flow: https://www.androidauthority.com/how-to-make-whatsapp-stickers-3073738 ; https://www.slashgear.com/1486960/how-to-create-custom-whatsapp-stickers
- Sticker.ly flow and limits: https://yourstory.com/2020/01/app-friday-photo-editing-stickerly-whatsapp-stickers
