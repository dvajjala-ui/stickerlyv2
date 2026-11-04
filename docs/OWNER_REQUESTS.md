# Things only the owner can do

Work continues around these; nothing here blocks the rest of the build. Newest first.

## 1. Create the new Supabase project (needed for Discover, the AI worker and Admin)
1. supabase.com → New project (free plan is fine to start). Region: Mumbai (`ap-south-1`).
2. Settings → API: copy **Project URL**, **anon public key**, **service_role key**. Settings → Database: the **DB password**.
3. Put them in `stickerdock-next/.env` (copy `.env.example`; the file is git-ignored). Never commit them.
4. Optional but recommended: Authentication → Email → enable "Email OTP / magic link" for the Admin sign-in.
I will then apply `backend/supabase/migrations/*.sql`, deploy the `intake` function and make you the first admin.

## 2. Groq API keys
GitHub Actions secrets can be *used* by workflows but can never be read back — not by me, not by you. Copy the keys
again from console.groq.com → API Keys and paste them into `.env` as `GROQ_API_KEYS=key1,key2,...`.
Important (Groq's own rules, checked 2026-11-05): free limits are per **organization**, not per key, and their
acceptable-use policy forbids spreading usage over multiple accounts to get more free quota. Several keys from one
account give failover only. On the free plan the vision model allows roughly **60 image analyses per day**; the
worker does free local pre-screening on every image and spends Groq only on the most promising ones. A paid Groq
plan costs about $0.0035 per analysed image (≈ $10/month for 100 images/day).

## 3. GitHub repo for the new project
The new code lives only in `D:\5. GitUploaded\stickerdock-next` (local git). To get CI (Android build, iOS build on
macOS runners, worker cron), create a GitHub repo (private is fine) and add secrets: `SUPABASE_URL`,
`SUPABASE_SERVICE_ROLE_KEY`, `GROQ_API_KEYS`, `INTAKE_TOKEN`, plus Android signing secrets for release builds.
iPhone builds can only be compiled on macOS, i.e. in GitHub Actions — there is no Mac here.

## 4. Phone settings changed for overnight testing
`Stay awake while charging (USB)` was turned on so automated checks can run with the screen on.
Turn it off again: Developer options → Stay awake, or `adb shell svc power stayon false`.

## 5. Decisions recorded (from 2026-11-05)
- 18+ lane = adult humor with clothed imagery + a private offline vault for your own explicit stickers.
- Discover publishes collected memes/stills as found, with source credit and a one-click report/takedown that also
  blocks re-upload. People with standing Indian court orders (list in `protected_people`) are excluded automatically
  — publishing them would risk contempt of court, not just a takedown.
- New Supabase project; testing on your S24 Ultra.
