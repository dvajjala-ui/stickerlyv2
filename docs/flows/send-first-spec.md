# Flow spec: send-first (P0)

Turns the #1 complaint ("the add-to-WhatsApp flow is too long") into a buildable spec. Planning only — no code here.
Owner bar: a 6th-standard kid finds a sticker and sends it in **one tap**. Grounds: UX_RESEARCH_AND_GAPS.md,
research/architecture-and-patterns.md.

## Principle
**Send is the default verb, not "add to a pack".** Packs are a WhatsApp distribution detail, invisible to the user.
Every sticker, everywhere (Discover, search, library), has the same two actions: **Send** (primary) and **Save**
(secondary). Managing packs/export happens in the background.

## The one gesture
- **Tap a sticker = Send.** Opens the Android share sheet pre-loaded with that single sticker image (sticker MIME), so
  the user picks a chat in WhatsApp (or any app) and it drops in. No pack, works for a single sticker. This is the path
  the research shows users expect (Telegram/Bitmoji: tap = send).
- **Long-press / "Save" icon = Save** to the user's library (an auto "Saved" pack). No naming, no pack picker, one tap.
- A small **"⋯"** reveals: Add to WhatsApp tray, Add to a pack, Report, Details — for power users, out of the main path.

## Even faster: the keyboard (the real habit)
The in-chat path beats any share sheet. Push the **StickerDock keyboard** in onboarding; once enabled, the user never
leaves the chat:
- Keyboard shows **Recents**, **Favourites**, **Search**, and pack tabs.
- Tap a sticker → it commits straight into the chat (we already implement `commitContent`).
- Search is instant and forgiving (Hinglish spelling, emoji) and sends on tap.
This is the end state; the tap-to-send share sheet is the fallback when the keyboard isn't enabled yet.

## Screens & states
1. **Discover / Library grid**
   - Each tile: image only (no name/code/status). Overlay on the tile: a tiny **Send** affordance; a **★** for save.
   - Tap tile body → **Send** (share sheet). Tap ★ → Save (toast "Saved", with Undo).
   - Empty state: a friendly line + "Browse packs"; never raw text or a blank area.
2. **Sticker preview (optional, on long-press)**
   - Big image, **Send** (full-width primary), **Save**, **⋯**. Escape/back closes. No technical fields.
3. **Pack sheet** (Discover)
   - Title + stickers. Primary: **"Add all to WhatsApp"** (handles the 3+ rule; see below). Each sticker still
     tap-to-send individually. "Copy link" + "Report" in the corner.
4. **Send confirmation**
   - After a send, a subtle toast "Sent to WhatsApp" is *not* claimed unless we can verify; prefer no false success.
     Just return to the grid.

## Handling WhatsApp's rules invisibly (so the user never hits them)
- **Single send** never needs a pack → always available.
- **"Add to WhatsApp tray"** for a user's saved stickers: maintain a background **"My StickerDock" tray pack**; when the
  user saves a sticker, add it there; if the tray pack has <3, **auto-pad** from their recents/favourites so WhatsApp
  accepts it, and say plainly "WhatsApp needs 3+ in a tray, so we added your recent ones." No dead-end error.
- **Discover packs** are pre-built ≥3 by the worker, so "Add all to WhatsApp" always works.
- **"Add to all my WhatsApps"**: one tap fans out to every detected WhatsApp (main, Business, Dual) — see UX plan §7.

## Edge cases
- WhatsApp not installed → Send still offers the share sheet (other apps); "Add to WhatsApp" is hidden with a hint.
- Sticker is animated (GIF) → same Send path; share as animated WebP.
- Offline → Save works (local); Send share sheet still works for already-downloaded stickers; Discover shows cached.
- 18+ sticker with the toggle off → not shown at all (not just hidden actions).
- Vault sticker → Send/Save as normal but never leaves the device via Discover/sync.

## Acceptance criteria (how we know it's done)
- From a cold open, sending a specific sticker to a WhatsApp chat takes **≤ 2 taps** (open app → tap sticker → pick
  chat) via share, or **≤ 2 taps inside a chat** via the keyboard.
- No user-facing screen shows a file name, SHA, status word, recipe, slug, or pack "series".
- Saving a sticker never asks for a name or a pack.
- A single sticker can be sent with no pack.
- Adding saved stickers to the WhatsApp tray never shows a "need 3 stickers" error.
- Works identically in Discover, search results, and the library.

## Build notes (for the engineer, not the user)
- Reuse `StickerKeyboardService` (commitContent) and the existing FileProvider/share path (`host/HostActions`,
  `MainActivity` ACTION_SEND). The work is mostly **surfacing** these as the primary action + hiding fields, not new
  plumbing.
- The auto "Saved"/"My StickerDock" tray pack + auto-pad is new logic in `library/Library.kt` + the provider shelf.
- Onboarding to enable the keyboard: a 3-step first-run (see flows/onboarding-spec.md, to follow).
- Verify on device: send to a real chat (owner present), keyboard-in-WhatsApp, animated send, both WhatsApp accounts.
