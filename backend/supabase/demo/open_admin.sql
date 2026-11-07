-- DEMO ONLY — open admin without login.
-- Grants the anonymous (publishable) key full access to the StickerDock *content* tables so the admin panel can run
-- with no sign-in (VITE_OPEN_ADMIN=true). This is deliberately insecure: anyone with the public key (it ships in the
-- storefront) can read/write/delete this demo content. It is scoped on purpose:
--   * it never exposes the secret key,
--   * it never touches auth,
--   * it never touches HandNavigate's collection_* tables (their data stays locked to the service role).
-- It is NOT in backend/supabase/migrations, so CI and real deployments stay secure. Apply it only to the demo
-- project. Reverse it with the DROP block at the bottom.
begin;

do $$
declare t text;
begin
  foreach t in array array[
    'settings','protected_people','source_images','trends','stickers','packs','pack_items','reports','takedowns'
  ] loop
    execute format('drop policy if exists "demo open admin" on public.%I', t);
    execute format('create policy "demo open admin" on public.%I for all to anon using (true) with check (true)', t);
  end loop;
  -- read-only panels (Overview, AI usage, worker runs)
  foreach t in array array['ai_calls','worker_runs'] loop
    execute format('drop policy if exists "demo open admin read" on public.%I', t);
    execute format('create policy "demo open admin read" on public.%I for select to anon using (true)', t);
  end loop;
end $$;

-- Source thumbnails live in the private `sources` bucket; let the anon key read and upload there (demo only).
drop policy if exists "demo open admin sources" on storage.objects;
create policy "demo open admin sources" on storage.objects for all to anon
  using (bucket_id = 'sources') with check (bucket_id = 'sources');

commit;

-- To reverse (lock it back down):
--   drop policy if exists "demo open admin" on public.settings;  (repeat per table above)
--   drop policy if exists "demo open admin read" on public.ai_calls;  drop policy if exists "demo open admin read" on public.worker_runs;
--   drop policy if exists "demo open admin sources" on storage.objects;
