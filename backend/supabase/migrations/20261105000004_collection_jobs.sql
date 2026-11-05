-- Collection queue for HandNavigate (the owner's image collector). Additive.
--
-- The worker's daily `plan` stage (or an admin) queues jobs: a few topics, the sources to search and an image limit.
-- HandNavigate runs on the owner's PC and polls outbound through the intake function (`claim`, then `report`
-- every ~15 s), so no tunnel or public endpoint is needed. Images still arrive through the intake function's
-- normal upload path as source_images rows; this table only tracks the requests.
--
-- Statuses mirror HandNavigate's own: QUEUED → RUNNING / WAITING_FOR_LOGIN → VERIFIED_STORAGE (done) or an
-- incomplete outcome (PARTIAL, AUTH_REQUIRED, DELIVERY_FAILED, FAILED, CANCELLED, INTERRUPTED).

create table public.collection_jobs (
  id uuid primary key default gen_random_uuid(),
  plan_date date not null default (now() at time zone 'Asia/Kolkata')::date,
  topics text[] not null check (cardinality(topics) between 1 and 10),
  sources text[] not null check (cardinality(sources) between 1 and 8),
  image_limit int not null check (image_limit between 1 and 100),
  status text not null default 'QUEUED' check (status in ('QUEUED','RUNNING','WAITING_FOR_LOGIN','VERIFIED_STORAGE',
    'PARTIAL','AUTH_REQUIRED','DELIVERY_FAILED','FAILED','CANCELLED','INTERRUPTED')),
  worker_id uuid,
  claimed_at timestamptz,
  reported_at timestamptz,
  saved int not null default 0 check (saved >= 0),
  delivered int not null default 0 check (delivered >= 0),
  error text,
  source_status jsonb not null default '{}',
  events jsonb not null default '[]',
  cancel_requested boolean not null default false,
  created_by text not null default 'worker' check (created_by in ('worker','admin')),
  note text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index collection_jobs_queue on public.collection_jobs(created_at) where status = 'QUEUED';
create index collection_jobs_day on public.collection_jobs(plan_date);
create trigger collection_jobs_touch before update on public.collection_jobs
  for each row execute function public.touch_updated_at();

alter table public.collection_jobs enable row level security;
create policy "admins manage collection jobs" on public.collection_jobs for all to authenticated
  using (public.is_admin()) with check (public.is_admin());

-- Next queued job for a collector. A job whose collector went quiet for 15 minutes is offered again first;
-- HandNavigate keys its local copy on the job id, so a re-claim resumes instead of starting over.
create or replace function public.claim_collection_job(p_worker uuid, p_sources text[] default null)
returns setof public.collection_jobs
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  update public.collection_jobs set status = 'QUEUED', worker_id = null
   where status in ('RUNNING', 'WAITING_FOR_LOGIN')
     and coalesce(reported_at, claimed_at, created_at) < now() - interval '15 minutes';
  return query
  update public.collection_jobs j
     set status = 'RUNNING', worker_id = p_worker, claimed_at = now(), reported_at = now()
   where j.id = (select q.id from public.collection_jobs q
                  where q.status = 'QUEUED' and not q.cancel_requested
                    and (p_sources is null or cardinality(p_sources) = 0 or q.sources && p_sources)
                  order by q.created_at
                  limit 1
                  for update skip locked)
  returning j.*;
end $$;

-- Progress from the collector that holds the job. Returns nothing when it isn't that collector's job any more
-- (requeued and claimed elsewhere); the intake function then answers 400 so HandNavigate stops its copy.
create or replace function public.report_collection_job(p_worker uuid, p_job uuid, p_status text, p_saved int,
  p_delivered int, p_error text default null, p_source_status jsonb default '{}', p_events jsonb default '[]')
returns setof public.collection_jobs
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if p_status not in ('RUNNING','WAITING_FOR_LOGIN','VERIFIED_STORAGE','PARTIAL','AUTH_REQUIRED','DELIVERY_FAILED',
                      'FAILED','CANCELLED','INTERRUPTED') then
    raise exception 'bad collection status %', p_status;
  end if;
  return query
  update public.collection_jobs
     set status = p_status, saved = greatest(coalesce(p_saved, 0), 0), delivered = greatest(coalesce(p_delivered, 0), 0),
         error = left(p_error, 500), source_status = coalesce(p_source_status, '{}'::jsonb),
         events = coalesce(p_events, '[]'::jsonb), reported_at = now()
   where id = p_job and worker_id = p_worker
  returning *;
end $$;

revoke all on function public.claim_collection_job(uuid, text[]) from public, anon, authenticated;
revoke all on function public.report_collection_job(uuid, uuid, text, int, int, text, jsonb, jsonb) from public, anon, authenticated;
grant execute on function public.claim_collection_job(uuid, text[]) to service_role;
grant execute on function public.report_collection_job(uuid, uuid, text, int, int, text, jsonb, jsonb) to service_role;

-- Owner settings for the daily plan (off until HandNavigate is pointed at this project).
insert into public.settings(key, value) values
  ('collect', '{"enabled": false, "daily_images": 100, "job_size": 25, "topics_per_job": 3,
                "sources": ["pinterest", "reddit", "imgflip", "flickr"], "owner_topics": []}')
on conflict (key) do nothing;
