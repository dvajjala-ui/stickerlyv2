-- Quality gate for worker-made stickers (backlog #14). Additive: one more sticker status, four nullable or
-- defaulted columns, a partial index and a settings row. Existing rows and data are untouched.
--
-- `held` = the content policy would publish the sticker, but the worker's quality score is below
-- settings.quality_gate.min_score. Like `draft`, it waits for an admin (publish = set status 'published').
-- Phones never see it: the RLS read policy, discover_stickers, discover_packs, discover_pack_stickers and
-- search_stickers all filter on status = 'published', and packs.sticker_count counts published stickers only.

-- Widen the status check (Postgres names the inline column check stickers_status_check).
alter table public.stickers drop constraint stickers_status_check;
alter table public.stickers add constraint stickers_status_check
  check (status in ('draft','published','held','rejected','taken_down'));

alter table public.stickers
  add column quality_score smallint check (quality_score is null or quality_score between 0 and 100),
  add column quality_reasons text[] not null default '{}',
  add column quality_signals jsonb,
  add column render_phash text check (render_phash is null or render_phash ~ '^[0-9a-f]{16}$');

comment on column public.stickers.quality_score is
  'Worker quality gate, 0-100: local heuristics, optionally blended with a vision judge. Below settings.quality_gate.min_score a sticker is held.';
comment on column public.stickers.quality_reasons is
  'Machine-readable gate reasons, worst first (blurry, tiny_subject, caption_low_contrast, near_duplicate, judge:<issue>, ...).';
comment on column public.stickers.quality_signals is
  'Gate measurements: coverage, sharpness, caption contrast, duplicate match, animation timing, judge verdict.';
comment on column public.stickers.render_phash is
  'pHash of the finished sticker''s visible area (near-duplicate check between stickers). phash stays the source pHash.';

-- Admin review queue for held stickers.
create index stickers_held on public.stickers(created_at desc) where status = 'held';

-- Owner settings for the gate; the worker falls back to the same defaults when the row is missing.
insert into public.settings(key, value) values
  ('quality_gate', '{"enabled": true, "min_score": 60, "vision_judge": false, "judge_band": 20}')
on conflict (key) do nothing;
