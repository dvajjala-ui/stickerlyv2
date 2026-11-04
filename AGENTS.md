# Working instructions — StickerDock (v2, `stickerdock-next`)

Read `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/CONTENT_POLICY.md` and the top of
`docs/WORKLOG.md` before changing anything. `docs/STATUS.md` is the only place that claims
what works; every claim there links to a test, build or device check.

## What this is
A clean rebuild of StickerDock (the v1 repo `../sticker` stays as reference and keeps running).
Two core capabilities, nothing else until they work end to end:
1. **WhatsApp everywhere** — one sticker library that reaches every WhatsApp on the phone
   (WhatsApp, Business, Dual Messenger/clone, WhatsApp's second account) and stays in sync.
2. **AI sticker factory** — understand collected images, make stickers (direct, recaption,
   cutout, motion, remake), publish individual stickers and auto-built packs to Discover.

## Owner decisions (2026-11-05)
- 18+ lane = adult humor (double meaning, spicy, savage, dark) with clothed art, age-gated and
  blurred until opted in, plus a private offline-only vault for users' own explicit imports.
- Discover may publish collected memes/stills as found, with source credit and a working
  report/takedown flow that also blocks re-ingestion.
- New Supabase project (separate from v1). Keys live only in local `.env` / CI secrets.
- Device testing on the owner's Galaxy S24 Ultra over USB debugging.

## Hard rules (not owner-adjustable)
- Never publish sexually explicit material (nudity, sex acts). Never sexualise a real,
  identifiable person. Anything showing a child is never 18+ and never paired with adult text.
- When driving the owner's phone over adb: never open chats, send messages, or change
  WhatsApp/account settings. Install/uninstall our own builds and read state only, unless
  the owner is present and asks.
- No secrets in Git. No paid services or public deployments without owner approval.
- Never silently delete user data on import errors; migrations are additive and tested.
- v1 code is the owner's MIT code and may be reused; StickerVault (GPL-3.0) may not be copied.

## Toolchain on the owner's PC
JDK 21 `D:\dev\jdk\jdk-21.0.13+9`, Android SDK `D:\dev\android-sdk`, Gradle home
`D:\dev\gradle-home`, adb `D:\dev\android-sdk\platform-tools\adb.exe`. Source
`tools/dev-env.ps1` (PowerShell) or `tools/dev-env.sh` (Git Bash) first.
Python 3.12: `../sticker/.venv` base interpreter; the worker has its own venv.
