-- W2 (review 2026-11-07, verified 2026-11-11): an adult-rated sticker inside a general-rated
-- published pack was served to anon users by discover_pack_stickers (no rating filter), leaking
-- 18+ content into the default feed. Packs must never mix ratings: serve only stickers whose
-- rating equals the pack's rating. The admin also blocks rating changes that would break this.
create or replace function public.discover_pack_stickers(p_pack uuid) returns setof public.discover_stickers
language sql stable security invoker set search_path = public, pg_temp as $$
  select d.* from public.pack_items i
  join public.discover_stickers d on d.id = i.sticker_id
  join public.packs p on p.id = i.pack_id and p.status = 'published'
  where i.pack_id = p_pack and d.rating = p.rating
  order by i.position
  limit 30;
$$;