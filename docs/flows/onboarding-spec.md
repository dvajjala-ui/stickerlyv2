# Flow spec: first-run onboarding (P1)

Planning only — no code. Goal: in ~30 seconds, a non-technical user understands the one thing that matters (how to send
a sticker fast) and the two power features (both WhatsApps, fresh daily). No jargon, no accounts, skippable.

## Why
Today the app opens on an empty "My stickers" with a wall of text and a developer-shaped flow. A first-timer doesn't
learn the fast path (tap = send; enable the keyboard). Onboarding is the cheapest retention lever after the send-first
flow itself.

## Design rules
- 3 cards max, swipeable, each with one picture + one line. A persistent **Skip**.
- Plain Hinglish-friendly words, 6th-standard reading level. No "pack", "provider", "RLS", "import".
- Every step is optional; nothing blocks reaching Discover.
- Ask for a permission only at the moment it pays off (just-in-time), never upfront.

## The 3 cards
1. **"Find a sticker you love."** Shows Discover with memes. Line: *"Hundreds of desi stickers, fresh every day. Tap one
   to send."* CTA: **See stickers** → Discover.
2. **"Send it in any chat — instantly."** Shows the keyboard sending into WhatsApp. Line: *"Turn on the StickerDock
   keyboard once, then send stickers right inside your chats."* CTA: **Turn on keyboard** → opens the system keyboard
   settings deep-link; **Maybe later** skips. (This is the habit-forming step.)
3. **"Works on both your WhatsApps."** Shows personal + Business/Dual. Line: *"Add your stickers to WhatsApp and WhatsApp
   Business together — no double work."* CTA: **Done** → Discover.

## Just-in-time prompts (not in the 3 cards)
- **Notifications** (Android 13+): ask only when the user saves their first sticker or follows a pack — "Want a ping when
  fresh packs drop?" Never on first launch.
- **Keyboard**: if skipped in card 2, re-offer the first time they tap **Send** ("Fastest way: turn on the keyboard").
- **Second WhatsApp**: offer the guided Dual/Business setup the first time they add a pack and we detect a second
  WhatsApp installed.

## Empty states (part of onboarding's job)
- Library empty → "Your saved stickers show here. Tap ★ on any sticker to save it." + a button to Discover.
- Discover still filling (no content) → "Fresh packs are on the way — check back soon." (never a blank or an error).
- Search no results → "Nothing for '<q>'. Try a mood like 'angry' or 'monday'." + popular chips.

## Accessibility / reach
- Large tap targets, primary actions bottom-reachable (one-handed).
- Consider a Hindi UI language option later (localization doc, future).
- Respect reduced-motion; cards don't autoplay loud/animated content.

## Acceptance criteria
- A new user reaches Discover in ≤ 3 taps (or 1 "Skip").
- The keyboard-enable path is offered at least twice (card 2 + first Send) but never forced.
- No permission is requested on first launch; each is asked in context.
- No empty screen ever shows raw/blank or an error; each has a friendly line + next action.

## Build notes
- A tiny first-run flag in local prefs; show once, re-showable from Settings → "How it works".
- Keyboard deep-link: `Settings.ACTION_INPUT_METHOD_SETTINGS` / enable flow already used by the Settings screen.
- Reuse existing screens; this is new presentation + copy, not new backend.
