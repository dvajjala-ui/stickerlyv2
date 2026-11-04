-- StickerDock v2 core schema.
-- Sources (collected images + AI understanding) → stickers (individual, first-class) → packs.
-- Phones read only published rows through RLS + the discover_* views/RPCs.
-- Workers use the service role. Admins are authenticated users listed in public.admins.

create extension if not exists pgcrypto;
create extension if not exists pg_trgm;
create extension if not exists vector;

-- ---------------------------------------------------------------- helpers
create or replace function public.touch_updated_at() returns trigger
language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;

create table public.admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  email text not null,
  created_at timestamptz not null default now()
);
alter table public.admins enable row level security;

create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select exists (select 1 from public.admins where user_id = auth.uid());
$$;

create policy "admins read admins" on public.admins for select to authenticated using (public.is_admin());

-- ---------------------------------------------------------------- settings
create table public.settings (
  key text primary key,
  value jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.settings enable row level security;
create policy "admins manage settings" on public.settings for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

insert into public.settings(key, value) values
  ('publish_policy', '{"auto_publish": true, "min_quality": 6, "adult_lane": true, "celebrities": "publish", "require_review_sites": []}'),
  ('budgets', '{"vision_per_day": 60, "text_per_day": 400, "image_gen_per_day": 0, "max_new_stickers_per_day": 120}'),
  ('categories', '{"enabled": ["reactions","funny","hinglish","movies","old_movies","songs","memes","dark","relationship","office","festivals","animals","baby","trending","adult"]}');

-- People with standing Indian court orders against unauthorised use of their persona (memes/GIFs/AI).
create table public.protected_people (
  name text primary key,
  note text,
  created_at timestamptz not null default now()
);
alter table public.protected_people enable row level security;
create policy "admins manage protected people" on public.protected_people for all to authenticated
  using (public.is_admin()) with check (public.is_admin());
insert into public.protected_people(name, note) values
  ('Amitabh Bachchan', 'Delhi HC 2022 order'), ('Anil Kapoor', 'Delhi HC 2023 order (incl. GIFs, jhakaas)'),
  ('Aishwarya Rai Bachchan', 'Delhi HC 2025 order'), ('Abhishek Bachchan', 'Delhi HC 2025 order'),
  ('Karan Johar', 'Delhi HC 2025 order'), ('Hrithik Roshan', 'Delhi HC 2025 order'),
  ('Jackie Shroff', 'Delhi HC 2024 order (memes partly allowed)'), ('Arijit Singh', 'Bombay HC 2024 order');

-- ---------------------------------------------------------------- sources
create table public.source_images (
  id uuid primary key default gen_random_uuid(),
  sha256 text not null unique check (sha256 ~ '^[0-9a-f]{64}$'),
  phash text check (phash is null or phash ~ '^[0-9a-f]{16}$'),
  storage_path text not null unique,            -- private bucket "sources"
  mime text not null,
  width int, height int,
  bytes int not null check (bytes > 0),
  frames int not null default 1,
  duration_ms int not null default 0,
  site text not null,                            -- reddit | pinterest | imgflip | openverse | upload | ...
  source_url text,
  page_url text,
  author text,
  license text,                                  -- as reported by the source; a hint, not verified
  collector text not null default 'unknown',     -- handnavigate | admin | worker-<adapter>
  topic_hint text,
  collected_at timestamptz not null default now(),
  status text not null default 'new'
    check (status in ('new','screened','analyzing','analyzed','used','rejected','failed','taken_down')),
  attempts int not null default 0,
  locked_until timestamptz,
  last_error text,
  -- tier 1: local pre-screen (free, every image)
  prescreen jsonb,
  prescreen_score real,
  ocr_text text,
  -- tier 2: AI understanding (Groq vision, budgeted)
  analysis jsonb,
  kind text,                                     -- meme | reaction | scene | cartoon | text_post | animal | baby | couple | other
  has_text boolean,
  language text,
  category text,
  mood text[] not null default '{}',
  tags text[] not null default '{}',
  emojis text[] not null default '{}',
  description text,
  nsfw text check (nsfw is null or nsfw in ('none','suggestive','explicit')),
  minors boolean,
  real_person boolean,
  public_figure text,
  sticker_score smallint check (sticker_score is null or sticker_score between 0 and 10),
  analyzed_at timestamptz,
  search_text text,
  embedding vector(384),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index source_images_queue on public.source_images(status, collected_at);
create index source_images_phash on public.source_images(phash);
create index source_images_search_fts on public.source_images using gin (to_tsvector('simple', coalesce(search_text, '')));
create index source_images_search_trgm on public.source_images using gin (search_text gin_trgm_ops);
create trigger source_images_touch before update on public.source_images for each row execute function public.touch_updated_at();

create or replace function public.source_search_text() returns trigger
language plpgsql as $$
begin
  new.search_text := concat_ws(' ', new.description, array_to_string(new.tags, ' '), array_to_string(new.mood, ' '),
    new.category, new.kind, new.ocr_text, new.topic_hint, new.site, new.public_figure);
  return new;
end $$;
create trigger source_images_search before insert or update on public.source_images
  for each row execute function public.source_search_text();

alter table public.source_images enable row level security;
create policy "admins manage sources" on public.source_images for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------- trends
create table public.trends (
  id uuid primary key default gen_random_uuid(),
  day date not null default (now() at time zone 'Asia/Kolkata')::date,
  title text not null,
  summary text,
  keywords text[] not null default '{}',
  category text,
  source text not null default 'owner',          -- google_trends_in | pib | owner | ...
  source_urls text[] not null default '{}',
  score real not null default 0,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  unique (day, title)
);
alter table public.trends enable row level security;
create policy "admins manage trends" on public.trends for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------- stickers (first-class)
create table public.stickers (
  id uuid primary key default gen_random_uuid(),
  source_id uuid references public.source_images(id) on delete set null,
  recipe text not null check (recipe in ('direct','caption','cutout','motion','remake','original','upload')),
  kind text not null check (kind in ('static','animated')),
  rating text not null default 'general' check (rating in ('general','adult')),
  category text not null default 'reactions',
  caption text,
  language text,
  tags text[] not null default '{}',
  emojis text[] not null default '{}' check (cardinality(emojis) <= 3),
  description text,
  file_path text not null unique,                -- public bucket "stickers"
  sha256 text not null unique check (sha256 ~ '^[0-9a-f]{64}$'),
  phash text,
  bytes int not null check (bytes > 0 and bytes <= 500000),
  ai_generated boolean not null default true,    -- IT Rules 2026: label AI-generated visual content
  credit jsonb,                                   -- {site, url, author}
  quality smallint check (quality is null or quality between 0 and 10),
  trend_id uuid references public.trends(id) on delete set null,
  status text not null default 'draft' check (status in ('draft','published','rejected','taken_down')),
  review_note text,
  search_text text,
  embedding vector(384),
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index stickers_feed on public.stickers(status, rating, published_at desc);
create index stickers_category on public.stickers(status, category, published_at desc);
create index stickers_source on public.stickers(source_id);
create index stickers_search_fts on public.stickers using gin (to_tsvector('simple', coalesce(search_text, '')));
create index stickers_search_trgm on public.stickers using gin (search_text gin_trgm_ops);
create trigger stickers_touch before update on public.stickers for each row execute function public.touch_updated_at();

create or replace function public.sticker_search_text() returns trigger
language plpgsql as $$
begin
  new.search_text := concat_ws(' ', new.caption, new.description, array_to_string(new.tags, ' '),
    array_to_string(new.emojis, ' '), new.category);
  if new.status = 'published' and new.published_at is null then new.published_at := now(); end if;
  return new;
end $$;
create trigger stickers_search before insert or update on public.stickers
  for each row execute function public.sticker_search_text();

alter table public.stickers enable row level security;
create policy "published stickers are public" on public.stickers for select to anon, authenticated
  using (status = 'published');
create policy "admins manage stickers" on public.stickers for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------- packs
create table public.packs (
  id uuid primary key default gen_random_uuid(),
  slug text unique,
  title text not null check (char_length(title) between 1 and 128),
  description text,
  kind text not null check (kind in ('static','animated')),
  rating text not null default 'general' check (rating in ('general','adult')),
  category text,
  pack_type text not null default 'series' check (pack_type in ('daily','series','situation','trend','manual')),
  series text,                                    -- growing series key, e.g. 'office-pain'
  volume int not null default 1,
  cover_sticker_id uuid references public.stickers(id) on delete set null,
  version int not null default 1,                 -- bump on every change; phones follow it
  sticker_count int not null default 0,
  status text not null default 'draft' check (status in ('draft','published','archived','taken_down')),
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (series, kind, rating, volume)
);
create trigger packs_touch before update on public.packs for each row execute function public.touch_updated_at();

create table public.pack_items (
  pack_id uuid not null references public.packs(id) on delete cascade,
  sticker_id uuid not null references public.stickers(id) on delete cascade,
  position int not null,
  added_at timestamptz not null default now(),
  primary key (pack_id, sticker_id)
);
create index pack_items_sticker on public.pack_items(sticker_id);

-- Keep count/version honest whatever writes the items.
create or replace function public.pack_items_changed() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  target uuid := coalesce(new.pack_id, old.pack_id);
begin
  update public.packs p set
    sticker_count = (select count(*) from public.pack_items i join public.stickers s on s.id = i.sticker_id
                     where i.pack_id = target and s.status = 'published'),
    version = p.version + 1
  where p.id = target;
  return null;
end $$;
create trigger pack_items_count after insert or update or delete on public.pack_items
  for each row execute function public.pack_items_changed();

alter table public.packs enable row level security;
alter table public.pack_items enable row level security;
create policy "published packs are public" on public.packs for select to anon, authenticated using (status = 'published');
create policy "admins manage packs" on public.packs for all to authenticated using (public.is_admin()) with check (public.is_admin());
create policy "published pack items are public" on public.pack_items for select to anon, authenticated
  using (exists (select 1 from public.packs p where p.id = pack_id and p.status = 'published'));
create policy "admins manage pack items" on public.pack_items for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------- reports & takedowns
create table public.reports (
  id uuid primary key default gen_random_uuid(),
  target_type text not null check (target_type in ('sticker','pack')),
  target_id uuid not null,
  reason text not null check (reason in ('copyright','person','hate','sexual','minor','other')),
  details text check (details is null or char_length(details) <= 500),
  status text not null default 'open' check (status in ('open','actioned','dismissed')),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);
create index reports_open on public.reports(status, created_at);
alter table public.reports enable row level security;
create policy "admins manage reports" on public.reports for all to authenticated using (public.is_admin()) with check (public.is_admin());

-- Fingerprints that must never be ingested or published again.
create table public.takedowns (
  id uuid primary key default gen_random_uuid(),
  sha256 text,
  phash text,
  source_url text,
  reason text not null,
  report_id uuid references public.reports(id) on delete set null,
  created_at timestamptz not null default now(),
  check (sha256 is not null or phash is not null or source_url is not null)
);
create index takedowns_sha on public.takedowns(sha256);
create index takedowns_url on public.takedowns(source_url);
alter table public.takedowns enable row level security;
create policy "admins manage takedowns" on public.takedowns for all to authenticated using (public.is_admin()) with check (public.is_admin());

-- ---------------------------------------------------------------- AI usage ledger
create table public.ai_calls (
  id bigserial primary key,
  at timestamptz not null default now(),
  provider text not null,
  model text not null,
  key_slot int,
  purpose text not null,                          -- analyze | caption | judge | pack_title | trend | image
  ok boolean not null,
  status int,
  input_tokens int,
  output_tokens int,
  latency_ms int,
  error text
);
create index ai_calls_day on public.ai_calls(at);
alter table public.ai_calls enable row level security;
create policy "admins read ai calls" on public.ai_calls for select to authenticated using (public.is_admin());

create table public.worker_runs (
  id bigserial primary key,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  stage text not null,
  ok boolean,
  summary jsonb
);
alter table public.worker_runs enable row level security;
create policy "admins read worker runs" on public.worker_runs for select to authenticated using (public.is_admin());

-- ---------------------------------------------------------------- public API for phones
create view public.discover_stickers with (security_invoker = on) as
  select id, kind, rating, category, caption, tags, emojis, file_path, sha256, credit, ai_generated, published_at
  from public.stickers where status = 'published';

create view public.discover_packs with (security_invoker = on) as
  select p.id, p.title, p.description, p.kind, p.rating, p.category, p.version, p.sticker_count,
         (select s.file_path from public.stickers s where s.id = p.cover_sticker_id and s.status = 'published') as cover_path,
         p.updated_at
  from public.packs p where p.status = 'published' and p.sticker_count >= 3;

grant select on public.discover_stickers, public.discover_packs to anon, authenticated;

create or replace function public.discover_pack_stickers(p_pack uuid) returns setof public.discover_stickers
language sql stable security invoker set search_path = public, pg_temp as $$
  select d.* from public.pack_items i
  join public.discover_stickers d on d.id = i.sticker_id
  join public.packs p on p.id = i.pack_id and p.status = 'published'
  where i.pack_id = p_pack
  order by i.position
  limit 30;
$$;

create or replace function public.search_stickers(q text, include_adult boolean default false, max_rows int default 60)
returns setof public.discover_stickers
language sql stable security invoker set search_path = public, pg_temp as $$
  select d.* from public.stickers s
  join public.discover_stickers d on d.id = s.id
  where s.status = 'published'
    and (include_adult or s.rating = 'general')
    and char_length(trim(q)) between 1 and 80
    and (to_tsvector('simple', coalesce(s.search_text, '')) @@ plainto_tsquery('simple', q)
         or s.search_text % q
         or s.search_text ilike '%' || replace(replace(q, '%', ''), '_', '') || '%')
  order by greatest(similarity(coalesce(s.search_text, ''), q),
                    ts_rank(to_tsvector('simple', coalesce(s.search_text, '')), plainto_tsquery('simple', q))) desc,
           s.published_at desc
  limit least(greatest(max_rows, 1), 200);
$$;

-- Anyone may report; a report never changes content by itself (admins or the worker act on it).
create or replace function public.report_content(target_type text, target_id uuid, reason text, details text default '')
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if target_type not in ('sticker','pack') then raise exception 'bad target'; end if;
  if (select count(*) from public.reports r where r.target_id = report_content.target_id and r.created_at > now() - interval '1 hour') > 50 then
    return; -- flood guard
  end if;
  insert into public.reports(target_type, target_id, reason, details)
  values (report_content.target_type, report_content.target_id, report_content.reason, nullif(left(coalesce(details, ''), 500), ''));
end $$;

grant execute on function public.discover_pack_stickers(uuid) to anon, authenticated;
grant execute on function public.search_stickers(text, boolean, int) to anon, authenticated;
grant execute on function public.report_content(text, uuid, text, text) to anon, authenticated;

-- ---------------------------------------------------------------- worker queue
create or replace function public.claim_sources(p_status text, p_next text, p_limit int, p_lease_seconds int default 900)
returns setof public.source_images
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  return query
  update public.source_images s
     set status = p_next, locked_until = now() + make_interval(secs => p_lease_seconds), attempts = s.attempts + 1
   where s.id in (
     select id from public.source_images
      where status = p_status and (locked_until is null or locked_until < now()) and attempts < 5
      order by collected_at
      limit least(greatest(p_limit, 1), 100)
      for update skip locked)
  returning s.*;
end $$;
revoke all on function public.claim_sources(text, text, int, int) from public, anon, authenticated;
grant execute on function public.claim_sources(text, text, int, int) to service_role;

-- Take down a sticker or pack everywhere, and remember its fingerprint.
create or replace function public.take_down(p_type text, p_id uuid, p_reason text, p_report uuid default null)
returns void
language plpgsql security definer set search_path = public, pg_temp as $$
declare s record;
begin
  if not (public.is_admin() or auth.role() = 'service_role') then raise exception 'not allowed'; end if;
  if p_type = 'sticker' then
    update public.stickers set status = 'taken_down', review_note = p_reason where id = p_id returning * into s;
    if found then
      insert into public.takedowns(sha256, phash, reason, report_id) values (s.sha256, s.phash, p_reason, p_report);
      if s.source_id is not null then
        update public.source_images set status = 'taken_down' where id = s.source_id;
        insert into public.takedowns(sha256, source_url, reason, report_id)
          select sha256, source_url, p_reason, p_report from public.source_images where id = s.source_id;
      end if;
      update public.packs set version = version + 1 where id in (select pack_id from public.pack_items where sticker_id = p_id);
    end if;
  elsif p_type = 'pack' then
    update public.packs set status = 'taken_down' where id = p_id;
  end if;
  if p_report is not null then update public.reports set status = 'actioned', resolved_at = now() where id = p_report; end if;
end $$;
revoke all on function public.take_down(text, uuid, text, uuid) from public, anon;
grant execute on function public.take_down(text, uuid, text, uuid) to authenticated, service_role;

-- ---------------------------------------------------------------- storage
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('sources', 'sources', false, 10485760, array['image/png','image/jpeg','image/webp','image/gif','video/mp4','video/webm']),
       ('stickers', 'stickers', true, 512000, array['image/webp','image/png'])
on conflict (id) do nothing;

create policy "admins read sources" on storage.objects for select to authenticated
  using (bucket_id = 'sources' and public.is_admin());
create policy "admins write sources" on storage.objects for insert to authenticated
  with check (bucket_id = 'sources' and public.is_admin());
