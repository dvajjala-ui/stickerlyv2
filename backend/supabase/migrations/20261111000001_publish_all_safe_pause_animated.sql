-- Owner decisions 2026-11-11 (see docs/WORKLOG.md):
-- 1) Every sticker that passes the SAFETY policy publishes to Discover: quality gate off,
--    min_quality 0. The hard safety rules (worker policy.py) are not touched by this migration.
-- 2) Animated recipes (motion / clip) are paused until real video stickers are built; they
--    produced non-functional "growing image" animations.
-- Both are reversible in admin Settings (or by updating these rows). To revive GIF/video sources
-- parked while paused:
--   update public.source_images set status = 'analyzed', last_error = null
--   where last_error = 'animated_paused';
update public.settings
   set value = jsonb_set(value, '{min_quality}', '0'::jsonb), updated_at = now()
 where key = 'publish_policy';

update public.settings
   set value = jsonb_set(value, '{enabled}', 'false'::jsonb), updated_at = now()
 where key = 'quality_gate';

insert into public.settings(key, value) values
  ('recipes', '{"motion": false, "clip": false}')
  on conflict (key) do nothing;