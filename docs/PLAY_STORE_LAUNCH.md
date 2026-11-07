# Play Store launch readiness

Getting on Google Play is the #1 growth unlock (sideloading an APK scares non-technical users and kills the funnel —
see GROWTH_AND_MONETIZATION.md). This is the practical checklist: what's ready, what's needed, in order. Planning only.

## Why it matters
Our target user won't enable "install from unknown sources". No Play listing = no real distribution. Everything else
(great content, send-first flow) only pays off once people can one-tap install.

## Account & tracks
- **Google Play Developer account** (one-time $25) — owner action.
- Ship via tracks: **internal testing** (instant, up to 100 testers) → **closed/open testing** → **production**. Start
  internal the day the account exists; don't wait for "perfect".

## Technical readiness (mostly done)
- ✅ Signed release build pipeline (`tools/release.ps1`, keystore outside the repo — **back it up**; losing it means you
  can't update the app).
- ⚠ **App Bundle (.aab)** — Play requires AAB, not APK. Confirm `:androidApp:bundleRelease` works (we build APK today).
- ⚠ **targetSdk** must meet Play's current minimum (Play requires a recent targetSdk each year) — verify.
- ⚠ **minSdk 28** is fine, but the **missing Android-version guards (review A1)** must be fixed first — the app is
  currently broken on Android 9–12 (WhatsApp detection dead, creator crash). **Blocker for a wide launch.**
- ✅ 512×512 sticker rules, WhatsApp provider contract (verified in review).

## Store listing assets (need to create)
- App name, short (80 char) + full (4000 char) description — lean on the positioning in GROWTH_AND_MONETIZATION.md.
- **Icon** (512×512), **feature graphic** (1024×500), **phone screenshots** (≥2; use real Discover + send flow).
- A short promo video (optional, helps).
- Category: Communication / Entertainment. Tags: stickers, WhatsApp, Hinglish, memes.

## Policy & compliance (the parts people miss)
- **Privacy policy URL** — ready at `site/privacy.html` (host it; replace the placeholder contact). Required.
- **Terms of Service** — not written yet; add a simple one (`site/terms.html`) before production.
- **Data safety form** — declare honestly: no account, no personal data collected by the app; Discover fetches are
  standard network. We don't collect contacts/location/camera. This is easy for us *because* the app is privacy-light —
  a selling point.
- **Content rating questionnaire (IARC)** — the app has an **18+ lane** (adult humour, no nudity). Rate accordingly;
  keep 18+ **off by default** and behind the toggle (already so). Be truthful or risk removal.
- **Intellectual property** — the riskiest area for a sticker app. Memes/Pinterest content and celebrity likenesses can
  draw takedowns/strikes. Mitigations already in place or planned: `celebrities=review`, protected-people list,
  takedown flow, rights-status labels, prefer licensed/original/CC sources, watermark/handle handling
  (research/watermark-handling.md). **Prefer original AI-art + clearly-licensed content for the store build** to reduce
  strike risk; keep reaction-meme content conservative.
- **WhatsApp trademark** — don't imply official affiliation; "works with WhatsApp", not "WhatsApp Stickers™ app".
- **Impersonation/brand** — don't use others' logos in the listing.

## Pre-launch QA (tie to REVIEW + flows)
- Fix review blockers: A1 (version guards), A2 (creator crash), A3/A4 (backup/restore + vault), A6/A7/A9/A10 (delete/
  pack integrity). These are crashes/data-loss — must pass before production.
- Run the send-first flow + onboarding on a range of devices/Android versions (use Play's pre-launch report + Firebase
  Test Lab, both free, which exercise the app on many devices and catch the A1-type crashes automatically).
- Verify on both WhatsApp consumer + Business and a Dual Messenger clone.

## Suggested sequence
1. Create the developer account; set up **internal testing** with the current signed build (even with known gaps) to
   learn the submission flow.
2. Fix the **crash/data-loss blockers** (A1/A2/A3/A6/A9) and switch to **.aab**.
3. Write Terms, host privacy + terms, fill the Data Safety + IARC forms.
4. Make listing assets from the real app.
5. Closed test (friends) → fix feedback → open test → production.
6. Keep the store build **content-conservative** (original/licensed-leaning) while the moderation/rights story matures.

## Acceptance (ready for production when)
- No known crash or data-loss bug (review blockers closed).
- AAB builds and installs from internal testing; pre-launch report is clean on Android 9–15.
- Privacy + Terms hosted and linked; Data Safety + content rating submitted truthfully.
- The send-first flow + onboarding work for a first-time user with no instructions.
