-- Keep packs.sticker_count and packs.version honest when stickers change status or pack metadata changes.
-- Phones follow `version`, so any visible change must bump it exactly once.

create or replace function public.recount_pack(p_pack uuid) returns void
language sql security definer set search_path = public, pg_temp as $$
  update public.packs p set sticker_count = (
    select count(*) from public.pack_items i join public.stickers s on s.id = i.sticker_id
    where i.pack_id = p_pack and s.status = 'published')
  where p.id = p_pack;
$$;
revoke all on function public.recount_pack(uuid) from public, anon, authenticated;

-- A sticker being published, rejected or taken down changes what every pack containing it shows.
create or replace function public.sticker_status_changed() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if new.status is distinct from old.status then
    update public.packs p set
      sticker_count = (select count(*) from public.pack_items i join public.stickers s on s.id = i.sticker_id
                       where i.pack_id = p.id and s.status = 'published'),
      version = p.version + 1
    where p.id in (select pack_id from public.pack_items where sticker_id = new.id);
  end if;
  return null;
end $$;
create trigger stickers_status_recount after update of status on public.stickers
  for each row execute function public.sticker_status_changed();

-- Title/description/cover/kind/rating edits bump the version unless the writer already did.
create or replace function public.pack_meta_version() returns trigger
language plpgsql as $$
begin
  if (new.title, new.description, new.cover_sticker_id, new.kind, new.rating, new.status)
       is distinct from (old.title, old.description, old.cover_sticker_id, old.kind, old.rating, old.status)
     and new.version = old.version then
    new.version := old.version + 1;
  end if;
  return new;
end $$;
create trigger packs_meta_version before update on public.packs
  for each row execute function public.pack_meta_version();
