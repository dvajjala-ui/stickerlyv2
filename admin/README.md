# StickerDock admin

Web panel for the StickerDock v2 backend (Supabase): review collected sources, edit and publish stickers, build
packs, act on reports, steer trends and tune the worker. Vite + React 19 + TypeScript + React Router 8 +
`@supabase/supabase-js`, plain CSS. It is a static SPA: no server of its own, every permission is enforced by
Postgres RLS (`public.is_admin()` / `public.admins`) and storage policies.

## Quick start (demo mode, no backend)

```bash
cd admin
npm install
npm run dev          # http://localhost:5173
```

Without `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` the panel runs on generated in-memory fixtures (Hinglish
stickers, sources, packs, reports, trends, AI usage, and seven stickers held by the quality gate, drawn blurry,
tiny or with an unreadable caption to match their reasons) under a lime **Demo data** banner. Edits work but reset on
reload. Demo sign-in: `owner@stickerdock.demo` is an admin; any other address shows the "Not authorised" screen.
`VITE_DEMO=true` forces demo mode even when the env is set.

| script | does |
| --- | --- |
| `npm run dev` | dev server (demo mode unless `.env.local` is filled in) |
| `npm run build` | `tsc -b` type-check, then the production bundle in `dist/` |
| `npm test` | vitest: demo API layer, pack/sticker rules, quality gate labels and settings, held-review request shapes (real supabase-js, in-memory `fetch`), emoji limit, SHA-256, intake function helpers. No network |
| `npm run lint` | oxlint |
| `npm run preview` | serve `dist/` locally |

## Connect to Supabase

1. Copy `.env.example` to `.env.local` (git-ignored) and set
   - `VITE_SUPABASE_URL=https://<project-ref>.supabase.co`
   - `VITE_SUPABASE_ANON_KEY=<anon key, or the newer sb_publishable_… key>`

   Both are public by design. **Never** put the service-role/secret key in any `VITE_*` variable: Vite ships those
   to the browser.
2. Apply `backend/supabase/migrations` to the project (schema, RLS, buckets `sources` (private) and `stickers` (public)).
   The panel selects the quality gate columns, so `20261105000003_quality_gate.sql` must be applied before this
   version is deployed; without it every sticker query fails.
3. Authentication → URL configuration: set **Site URL** to the deployed admin URL and add redirect URLs for
   `http://localhost:5173/**` and `https://<admin-domain>/**`. The magic link returns to the page origin.
4. Authentication → Email templates → Magic link: optionally add `{{ .Token }}` so the email also carries a
   6-digit code. The sign-in screen accepts it, which helps when the link is opened on another device.
5. Supabase's built-in mailer is rate-limited and only delivers to members of your Supabase organisation. That
   is enough for the owner; configure custom SMTP before adding anyone else.

### Sign-in and the first admin

Sign-in is a one-time email link (`signInWithOtp`, implicit flow so the link also works when opened in a
different browser) or the 6-digit code. The session is stored in `localStorage` and refreshed automatically, so
each device signs in once; "Sign out" ends only that device's session.

Admin = a Supabase Auth user listed in `public.admins`. The panel can't grant that itself (by design):

1. Open the panel and sign in once with the owner's email. This creates the `auth.users` row; you will see
   **Not authorised**, with your user id and a ready-made SQL statement.
2. In the Supabase dashboard → SQL editor run:
   ```sql
   insert into public.admins (user_id, email)
   select id, email from auth.users where email = 'owner@example.com';
   ```
3. Click **Check again**.
4. Optional hardening: Authentication → Sign In / Providers → turn off "Allow new users to sign up" once the
   admins exist. Strangers then can't create auth users at all (they could never read anything anyway).

## Deploy (static SPA; owner approval needed)

`npm run build` outputs `dist/`. Any static host works if unknown paths fall back to `index.html`.

- **Vercel**: import the repo, Root Directory `admin`, framework preset Vite (build `npm run build`, output
  `dist`), set the two `VITE_*` variables. `vercel.json` adds the SPA rewrite and security headers. Keep
  "Include files outside the root directory in the Build Step" on (the default): the build imports
  `backend/supabase/functions/intake/lib.ts`.
- **Cloudflare Pages**: root directory `admin`, build `npm run build`, output `dist`, env vars plus
  `NODE_VERSION=24`. Pages serves `index.html` for unknown paths because there is no `404.html`;
  `public/_headers` sets the security headers.

Afterwards add the deployed URL to Supabase's redirect URLs (step 3 above). The headers include
`X-Frame-Options: DENY` (one-click takedowns must not be clickjackable) and `X-Robots-Tag: noindex`.

## Pages

| page | what it does |
| --- | --- |
| Overview | today (IST) counts, AI calls vs `settings.budgets`, source pipeline by status, last `worker_runs` |
| Sources | grid with signed-URL thumbnails; filters (status, site, kind, category, nsfw, minors, has_text, score), search over `search_text`; drawer with original, provenance, AI fields + raw `analysis`/`prescreen` JSON; reject / re-analyze (→ `screened`, attempts reset); drag-and-drop upload (SHA-256 in the browser, magic-byte check, dedupe and takedown check before upload, row with `site=upload`, `collector=admin`). Explicit images stay blurred until clicked. |
| Stickers | tabs by status (Drafts, **Held**, Published, Rejected, Taken down, All), filters, search, sort (newest, published, quality, gate score lowest first, size); multi-select (shift-click ranges) → publish / to draft / reject / take down; drawer editor for caption, tags, 1–3 emojis, category, rating, AI-generated label, review note; credit, packs containing it, WhatsApp size badge. Tiles and the drawer show the quality gate score (green at or above the minimum, amber below it, red when a hard problem caps it) and readable reasons; the drawer adds the vision judge's verdict, a link to the sticker a near-duplicate repeats, the render pHash and the raw `quality_signals` (collapsed) |
| Stickers → Held | the worker's quality gate queue: stickers the policy would publish but that scored below `quality_gate.min_score`. Newest first (the `stickers_held` index order). **Approve selected** publishes, **Reject selected** rejects; both ask to confirm in the selection bar, and any change of selection cancels the confirm. Approving applies the publish rules below (blocked stickers are skipped). Only rows still `held` change (`… where status = 'held'`, 50 ids per request), so a sticker changed elsewhere is reported, never overwritten. Every sticker that did not change is listed with why (publish rule, no longer held, or the request error) |
| Packs | list by type/status; editor with ordered stickers (drag, ↑/↓, ★ cover, remove), add-by-search limited to the pack's kind and rating, live rule checks, publish / unpublish / archive, version shown |
| Reports | open queue oldest first, grouped per target with preview, reason, age; **Take down** is one click (`take_down` RPC: removes it everywhere, records fingerprints, closes the reports) or Dismiss |
| Trends | topics per IST day; enable/disable; add owner topics (`source = 'owner'`) |
| Settings | `publish_policy`, `quality_gate` (on/off, minimum score 0–100, vision judge with its budget warning, judge band 0–100 with the score range it covers; whole numbers only), `budgets`, enabled `categories`, `protected_people` |

### Rules the panel enforces

- **Publishing a sticker** is blocked when the source is flagged sexually explicit, when a sticker showing a
  child is rated 18+, when it depicts or names someone on `protected_people`, when it has no emoji or more than
  3, when it is over WhatsApp's size limit (static 100 KB, animated 500 KB; KB = 1024 bytes), or when it was taken
  down. Real people in 18+ stickers and suggestive sources rated general raise warnings.
  Approving a held sticker is publishing it, so the same rules apply.
- **Packs**: 3–30 stickers, never static mixed with animated (a WhatsApp rule) nor 18+ mixed with general;
  saving is blocked while a composition error exists. Publishing also needs every sticker published and valid
  (the editor offers "publish N draft stickers"). Held stickers can't be added, and one already in a pack blocks
  publishing until it is approved in Stickers → Held or removed.

## Code layout

```
src/api/            one module per table, each exporting an interface plus supabaseX(sb) and demoX(db)
  sources, stickers, packs (+pack_items), reports, takedowns (RPC take_down), trends, settings,
  protectedPeople, aiCalls, workerRuns, auth
  compose.ts        builds the Api from either implementation;  index.ts  picks one at start-up
  demo/             in-memory tables, fixtures and generated SVG art (loaded only in demo mode)
src/lib/            pure rules and helpers (pack/sticker rules, quality gate labels/settings/review report, emoji,
                    sha256, media, formatting)
src/pages/, src/components/
```

`src/lib/media.ts` re-exports `backend/supabase/functions/intake/lib.ts`, so browser uploads and the intake
Edge Function share one implementation of magic-byte sniffing, dimension probing and the
`<yyyy>/<mm>/<sha256>.<ext>` storage path (`storage_path` is the path inside the `sources` bucket).

## Schema notes (for the backend owner)

- `pack_items_changed()` recounts `packs.sticker_count` (published stickers only) only when `pack_items` rows
  change, so publishing or taking down a sticker leaves its packs' counts stale (and `discover_packs` filters on
  `sticker_count >= 3`). The panel works around it by re-touching one `pack_items` row per affected pack after
  sticker status changes; a trigger on `stickers.status` would be the proper fix.
- Pack metadata edits bump `packs.version` from the panel (the schema only bumps it on item changes).
- The held review (`stickers.reviewHeld`) does not re-touch `pack_items`: held stickers are never packed, and
  migration 000002's `stickers_status_recount` trigger recounts any pack that holds one. `published_at` is set by
  the `stickers_search` trigger on first publish, as for the Publish action.
- Quality reason labels follow `backend/worker/README.md` (stage 3). An unknown reason code still shows, humanized.
