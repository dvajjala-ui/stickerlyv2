// packs + pack_items (ordered membership).

import type { SupabaseClient } from '@supabase/supabase-js'
import { PACK_MAX } from '../lib/packRules'
import { clone, delay, demoError, demoUuid, touchPackCounts, type DemoDb } from './demo/db'
import { STICKER_SELECT, demoStickerRow } from './shared'
import type {
  Iso,
  Pack,
  PackFilter,
  PackInput,
  PackItemRow,
  PackRef,
  PackRow,
  PackStatus,
  Page,
  PageReq,
  Uuid,
} from './types'
import { ApiError, matchesTerms, randomSuffix, searchTerms, slugify, unwrap, unwrapCount } from './util'

export type PackSettableStatus = Exclude<PackStatus, 'taken_down'>

export interface PacksApi {
  list(filter: PackFilter, page: PageReq): Promise<Page<PackRow>>
  get(id: Uuid): Promise<PackRow | null>
  items(packId: Uuid): Promise<PackItemRow[]>
  create(input: PackInput): Promise<Pack>
  /** Saves metadata and bumps `version` so phones refresh the pack. */
  update(id: Uuid, patch: Partial<PackInput>): Promise<Pack>
  /** Replaces the ordered sticker list. Only removed/moved/new rows are written. */
  setItems(packId: Uuid, stickerIds: Uuid[]): Promise<void>
  /** publish / unpublish (draft) / archive. A taken-down pack can't be changed here. */
  setStatus(id: Uuid, status: PackSettableStatus): Promise<Pack>
  forSticker(stickerId: Uuid): Promise<PackRef[]>
  countSince(column: 'created_at' | 'published_at', since: Iso): Promise<number>
  countLive(): Promise<number>
}

const PACK_COLUMNS =
  'id,slug,title,description,kind,rating,category,pack_type,series,volume,cover_sticker_id,version,sticker_count,status,published_at,created_at,updated_at'
// packs↔stickers has two paths (cover FK and pack_items), so the cover embed names its FK.
const PACK_SELECT = `${PACK_COLUMNS},cover:stickers!packs_cover_sticker_id_fkey(id,file_path,kind)`

function newSlug(title: string): string {
  return `${slugify(title) || 'pack'}-${randomSuffix()}`
}

function checkItemList(ids: Uuid[]): void {
  if (new Set(ids).size !== ids.length) throw new ApiError('A sticker can only appear once in a pack')
  if (ids.length > PACK_MAX) throw new ApiError(`A pack holds at most ${PACK_MAX} stickers`)
}

// ------------------------------------------------------------------ Supabase

export function supabasePacks(sb: SupabaseClient): PacksApi {
  async function getRow(id: Uuid): Promise<PackRow | null> {
    const res = await sb.from('packs').select(PACK_SELECT).eq('id', id).maybeSingle()
    return unwrap(res, 'Load pack') as unknown as PackRow | null
  }

  /** Re-touch one item so the pack_items trigger recounts published stickers. */
  async function recount(packId: Uuid): Promise<void> {
    const first = await sb.from('pack_items').select('sticker_id,position').eq('pack_id', packId).limit(1)
    const row = (unwrap(first, 'Load pack items') as { sticker_id: string; position: number }[])[0]
    if (!row) return
    const upd = await sb
      .from('pack_items')
      .update({ position: row.position })
      .eq('pack_id', packId)
      .eq('sticker_id', row.sticker_id)
    unwrap(upd, 'Refresh pack count')
  }

  return {
    async list(f, page) {
      let q = sb.from('packs').select(PACK_SELECT, { count: 'exact' })
      if (f.pack_type) q = q.eq('pack_type', f.pack_type)
      if (f.status) q = q.eq('status', f.status)
      for (const term of searchTerms(f.q)) q = q.ilike('title', `%${term}%`)
      const res = await q.order('updated_at', { ascending: false }).range(page.offset, page.offset + page.limit - 1)
      return { rows: unwrap(res, 'Load packs') as unknown as PackRow[], total: res.count ?? 0 }
    },

    get: getRow,

    async items(packId) {
      const res = await sb
        .from('pack_items')
        .select(`sticker_id,position,added_at,sticker:stickers(${STICKER_SELECT})`)
        .eq('pack_id', packId)
        .order('position')
      return unwrap(res, 'Load pack stickers') as unknown as PackItemRow[]
    },

    async create(input) {
      const res = await sb.from('packs').insert({ ...input, slug: newSlug(input.title) }).select(PACK_COLUMNS).single()
      return unwrap(res, 'Create pack') as unknown as Pack
    },

    async update(id, patch) {
      const cur = unwrap(await sb.from('packs').select('version').eq('id', id).single(), 'Load pack') as { version: number }
      const res = await sb
        .from('packs')
        .update({ ...patch, version: cur.version + 1 })
        .eq('id', id)
        .select(PACK_COLUMNS)
        .single()
      return unwrap(res, 'Save pack') as unknown as Pack
    },

    async setItems(packId, ids) {
      checkItemList(ids)
      const cur = unwrap(
        await sb.from('pack_items').select('sticker_id,position').eq('pack_id', packId),
        'Load pack items',
      ) as { sticker_id: string; position: number }[]
      const wanted = new Set(ids)
      const removed = cur.filter((c) => !wanted.has(c.sticker_id)).map((c) => c.sticker_id)
      if (removed.length) {
        unwrap(await sb.from('pack_items').delete().eq('pack_id', packId).in('sticker_id', removed), 'Remove stickers')
      }
      const curPos = new Map(cur.map((c) => [c.sticker_id, c.position]))
      const changed = ids
        .map((sticker_id, position) => ({ pack_id: packId, sticker_id, position }))
        .filter((r) => curPos.get(r.sticker_id) !== r.position)
      if (changed.length) {
        unwrap(await sb.from('pack_items').upsert(changed, { onConflict: 'pack_id,sticker_id' }), 'Save sticker order')
      }
    },

    async setStatus(id, status) {
      const cur = unwrap(
        await sb.from('packs').select('status,published_at').eq('id', id).single(),
        'Load pack',
      ) as Pick<Pack, 'status' | 'published_at'>
      if (cur.status === 'taken_down') throw new ApiError('This pack was taken down and stays down')
      const patch: Partial<Pack> = { status }
      if (status === 'published' && !cur.published_at) patch.published_at = new Date().toISOString()
      unwrap(await sb.from('packs').update(patch).eq('id', id), 'Update pack status')
      if (status === 'published') await recount(id)
      const row = await getRow(id)
      if (!row) throw new ApiError('Pack disappeared')
      return row
    },

    async forSticker(stickerId) {
      const res = await sb.from('pack_items').select('pack:packs(id,title,kind,rating,status)').eq('sticker_id', stickerId)
      const rows = unwrap(res, 'Load packs for sticker') as unknown as { pack: PackRef | null }[]
      return rows.map((r) => r.pack).filter((p): p is PackRef => p !== null)
    },

    async countSince(column, since) {
      const res = await sb.from('packs').select('id', { count: 'exact', head: true }).gte(column, since)
      return unwrapCount(res, 'Count packs')
    },

    async countLive() {
      const res = await sb.from('packs').select('id', { count: 'exact', head: true }).eq('status', 'published')
      return unwrapCount(res, 'Count packs')
    },
  }
}

// ------------------------------------------------------------------ demo

function demoRow(db: DemoDb, p: Pack): PackRow {
  const cover = p.cover_sticker_id ? db.stickers.find((s) => s.id === p.cover_sticker_id) : undefined
  return { ...clone(p), cover: cover ? { id: cover.id, file_path: cover.file_path, kind: cover.kind } : null }
}

function checkPackRow(db: DemoDb, p: Pack): void {
  if (p.title.length < 1 || p.title.length > 128) throw demoError('Save pack: title must be 1–128 characters', '23514')
  const clash = db.packs.find(
    (o) => o.id !== p.id && p.series !== null && o.series === p.series && o.kind === p.kind && o.rating === p.rating && o.volume === p.volume,
  )
  if (clash) throw demoError(`Save pack: already exists (series ${p.series} volume ${p.volume})`, '23505')
}

export function demoPacks(db: DemoDb): PacksApi {
  return {
    async list(f, page) {
      await delay(db)
      const terms = searchTerms(f.q)
      const rows = db.packs
        .filter(
          (p) =>
            (!f.pack_type || p.pack_type === f.pack_type) &&
            (!f.status || p.status === f.status) &&
            (!terms.length || matchesTerms(p.title, terms)),
        )
        .sort((a, b) => b.updated_at.localeCompare(a.updated_at))
      return { rows: rows.slice(page.offset, page.offset + page.limit).map((p) => demoRow(db, p)), total: rows.length }
    },

    async get(id) {
      await delay(db)
      const p = db.packs.find((x) => x.id === id)
      return p ? demoRow(db, p) : null
    },

    async items(packId) {
      await delay(db)
      return db.packItems
        .filter((i) => i.pack_id === packId)
        .sort((a, b) => a.position - b.position)
        .flatMap((i) => {
          const s = db.stickers.find((x) => x.id === i.sticker_id)
          return s ? [{ sticker_id: i.sticker_id, position: i.position, added_at: i.added_at, sticker: demoStickerRow(db, s) }] : []
        })
    },

    async create(input) {
      await delay(db)
      const at = new Date().toISOString()
      const p: Pack = {
        ...clone(input),
        id: demoUuid(),
        slug: newSlug(input.title),
        version: 1,
        sticker_count: 0,
        status: 'draft',
        published_at: null,
        created_at: at,
        updated_at: at,
      }
      checkPackRow(db, p)
      db.packs.unshift(p)
      return clone(p)
    },

    async update(id, patch) {
      await delay(db)
      const p = db.packs.find((x) => x.id === id)
      if (!p) throw demoError('Save pack: not found', 'PGRST116')
      const next: Pack = { ...p, ...clone(patch), version: p.version + 1, updated_at: new Date().toISOString() }
      checkPackRow(db, next)
      Object.assign(p, next)
      return clone(p)
    },

    async setItems(packId, ids) {
      await delay(db)
      checkItemList(ids)
      const at = new Date().toISOString()
      const before = db.packItems.filter((i) => i.pack_id === packId)
      const removed = before.filter((i) => !ids.includes(i.sticker_id)).length
      let changed = 0
      db.packItems = db.packItems.filter((i) => i.pack_id !== packId || ids.includes(i.sticker_id))
      ids.forEach((sticker_id, position) => {
        const item = db.packItems.find((i) => i.pack_id === packId && i.sticker_id === sticker_id)
        if (!item) {
          db.packItems.push({ pack_id: packId, sticker_id, position, added_at: at })
          changed++
        } else if (item.position !== position) {
          item.position = position
          changed++
        }
      })
      if (removed + changed > 0) touchPackCounts(db, packId, removed + changed) // one bump per row, like the trigger
    },

    async setStatus(id, status) {
      await delay(db)
      const p = db.packs.find((x) => x.id === id)
      if (!p) throw demoError('Update pack status: not found', 'PGRST116')
      if (p.status === 'taken_down') throw new ApiError('This pack was taken down and stays down')
      p.status = status
      p.updated_at = new Date().toISOString()
      if (status === 'published') {
        p.published_at ??= p.updated_at
        touchPackCounts(db, id)
      }
      return demoRow(db, p)
    },

    async forSticker(stickerId) {
      await delay(db)
      const packIds = db.packItems.filter((i) => i.sticker_id === stickerId).map((i) => i.pack_id)
      return db.packs
        .filter((p) => packIds.includes(p.id))
        .map((p) => ({ id: p.id, title: p.title, kind: p.kind, rating: p.rating, status: p.status }))
    },

    async countSince(column, since) {
      await delay(db)
      return db.packs.filter((p) => (p[column] ?? '') >= since).length
    },

    async countLive() {
      return db.packs.filter((p) => p.status === 'published').length
    },
  }
}
