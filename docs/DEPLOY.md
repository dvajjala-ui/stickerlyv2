# Deploying the websites (Vercel)

Two web things live in this repo, both deploy from the public GitHub repo `dvajjala-ui/stickerlyv2`:

| Site | Folder | Who uses it | Build |
| --- | --- | --- | --- |
| **Discover gallery** (public) | `site/` | everyone — browse published packs/stickers | none (static HTML) |
| **Admin panel** (login) | `admin/` | you — review sources, upload, edit, publish | Vite (`npm run build`) |

The cleanest "CI/CD" is Vercel's **Git integration**: connect the repo once, and every push to `main` auto-deploys.
No tokens, no GitHub secrets. Do this twice — once per site — because they have different root folders.

## 1. Public gallery  →  e.g. `stickerdock.vercel.app`
1. vercel.com → **Add New → Project** → import `dvajjala-ui/stickerlyv2`.
2. **Root Directory:** `site`  (click Edit, pick the `site` folder).
3. **Framework Preset:** Other.  **Build Command:** leave empty.  **Output Directory:** leave empty.
4. Deploy. That's it — it's a single static page; it reads the public catalog with the publishable key baked in
   (safe to ship). It fills automatically as the worker publishes stickers.

## 2. Admin panel  →  e.g. `stickerdock-admin.vercel.app`
1. vercel.com → **Add New → Project** → import the **same** repo again.
2. **Root Directory:** `admin`.
3. Framework **Vite** is detected: Build `npm run build`, Output `dist`. Leave as-is.
4. **Environment Variables** (Settings → Environment Variables), both public and safe to expose:
   - `VITE_SUPABASE_URL` = `https://kyvowdxtwazxhsdjajpm.supabase.co`
   - `VITE_SUPABASE_ANON_KEY` = `sb_publishable_hibpCfAaAFRgMc24KVWiCg_hpm8Zw1b`
   - **Never** put the `sb_secret_…` key here — Vite ships env vars to the browser.
5. Deploy.
6. In Supabase → Authentication → URL Configuration: set **Site URL** to the admin's Vercel URL and add it to
   **Redirect URLs** (plus `http://localhost:5173/**` for local dev). The magic-link returns to this origin.

### First admin sign-in (one time)
1. Open the admin URL, enter your email, click the magic link (or type the 6-digit code).
2. You'll see **Not authorised** with your user id. That's expected — it created your `auth.users` row.
3. Tell me, or run in Supabase → SQL Editor:
   ```sql
   insert into public.admins (user_id, email) values ('<your-user-id>', '<your-email>');
   ```
4. Reload — you're in. Now you can review sources, **upload images from your PC**, edit, and publish.

## Auto-publish (AI → users, no confirmation)
Already on: `settings.publish_policy.auto_publish = true`. Good stickers that pass the quality gate publish straight
to Discover. To require a manual review step instead, flip it off in admin → Settings.

## Alternative: GitHub Actions → Vercel (only if you prefer Actions over Vercel's Git integration)
Add repo secrets `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` and a workflow that runs
`vercel deploy --prod --token=$VERCEL_TOKEN`. The Git integration above does the same thing with no secrets, so
prefer it unless you specifically want the build to run in GitHub.

## Custom domain (optional)
Vercel project → Settings → Domains → add your domain and follow the DNS step. Point the gallery at your main
domain and the admin at an `admin.` subdomain if you like.
