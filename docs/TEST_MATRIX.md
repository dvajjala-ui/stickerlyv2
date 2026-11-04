# Device test matrix (owner's Galaxy S24 Ultra: Android 16, One UI 8.5; WhatsApp 2.26.39.74 in user 0 and user 95)

Status: ☐ not run · ✅ pass · ❌ fail · ⚠ partial. Evidence goes in `docs/STATUS.md` with date and build.

| # | Check | How | Status |
|---|---|---|---|
| A1 | Debug build installs and opens | `adb install`, launch | ☐ |
| A2 | Folder import of WhatsApp Stickers (1,000+), dedup, packs named from EXIF | Import from this phone | ☐ |
| A3 | Import survives leaving the app / process death and resumes | kill during import | ☐ |
| A4 | ZIP/.wastickers/v1 backup import | share or pick files | ☐ |
| P1 | Add pack to WhatsApp (user 0): WhatsApp's dialog appears, Added ✓ shows after | pack screen → Add | ☐ |
| P2 | Edit pack (add/remove sticker) → WhatsApp reloads (provider log shows re-query) | Diagnostics events | ☐ |
| P3 | More than 10 packs on the shelf accepted by WhatsApp | add 11+ | ☐ |
| P4 | Second account inside WhatsApp: pack goes to active account; switching + Add again | owner present | ☐ |
| D1 | `pm install-existing --user 95` works on One UI 8.5 | adb | ☐ |
| D2 | `pm grant [--user 95] … INTERACT_ACROSS_USERS` works | adb | ☐ |
| D3 | Companion reads main library over `content://0@…sync` | companion screen "Connected" | ☐ |
| D4 | Main app opens companion via CrossProfileApps; clone WhatsApp shows add dialog | Add → Dual | ☐ |
| D5 | Clone WhatsApp reads packs from companion provider, stickers display | owner present | ☐ |
| K1 | Keyboard enabled; test field receives `image/webp.wasticker` commit | Keyboard test | ☐ |
| K2 | WhatsApp composer MIME list captured (owner present; no message sent) | Diagnostics events | ☐ |
| K3 | Keyboard inside Dual Messenger (companion keyboard enabled for user 95) | owner present | ☐ |
| K4 | Sticker sent via keyboard arrives as a sticker (animated plays) | owner present | ☐ |
| X1 | Discover feed loads, save sticker/pack, pack follows updates | needs Supabase | ☐ |
