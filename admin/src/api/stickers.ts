// stickers (+ the public `stickers` storage bucket).

import type { SupabaseClient } from '@supabase/supabase-js'
import { delay, demoError, type DemoDb } from './demo/db'
import { STICKER_SELECT, demoStickerRow, refreshPackCounts, refreshPackCountsDemo } from './shared'
import type {
  Iso,
  Page,
  PageReq,
  Sticker,
  StickerFilter,
  StickerPatch,
  StickerRow,
  StickerStatus,
  Uuid,
} from './types'
import { matchesTerms, searchTerms, unwrap, unwrapCount } from './util'

/** Statuses an admin can set directly. `taken_down` goes through the take_down RPC only. */
export type StickerSettableStatus = Exclude<StickerStatus, 'taken_down'>

export interface StickersApi {
  list(filter: StickerFilter, page: PageReq): Promise<Page<StickerRow>>
  get(id: Uuid): Promise<StickerRow | null>
  byIds(ids: Uuid[]): Promise<StickerRow[]>
  bySource(sourceId: Uuid): Promise<StickerRow[]>
  update(id: Uuid, patch: StickerPatch): Promise<StickerRow>
  /** Bulk status change; rows that are taken_down are never changed. */
  setStatus(ids: Uuid[], status: StickerSettableStatus): Promise<void>
  publicUrl(filePath: string): string
  countSince(column: 'created_at' | 'published_at', since: Iso): Promise<number>
}

const MAX_IN = 200

// ------------------------------------------------------------------ Supabase

export function supabaseStickers(sb: SupabaseClient): StickersApi {
  return {
    async list(f, page) {
      let q = sb.from('stickers').select(STICKER_SELECT, { count: 'exact' })
      if (f.status) q = q.eq('status', f.status)
      if (f.category) q = q.eq('category', f.category)
      if (f.rating) q = q.eq('rating', f.rating)
      if (f.kind) q = q.eq('kind', f.kind)
      if (f.excludeIds?.length) q = q.not('id', 'in', `(${f.excludeIds.slice(0, MAX_IN).join(',')})`)
      for (const term of searchTerms(f.q)) q = q.ilike('search_text', `%${term}%`)
      if (f.sort === 'published') q = q.order('published_at', { ascending: false, nullsFirst: false })
      else if (f.sort === 'quality') q = q.order('quality', { ascending: false, nullsFirst: false })
      else if (f.sort === 'size') q = q.order('bytes', { ascending: false })
      q = q.order('created_at', { ascending: false }).range(page.offset, page.offset + page.limit - 1)
      const res = await q
      return { rows: unwrap(res, 'Load stickers') as unknown as StickerRow[], total: res.count ?? 0 }
    },

    async get(id) {
      const res = await sb.from('stickers').select(STICKER_SELECT).eq('id', id).maybeSingle()
      return unwrap(res, 'Load sticker') as unknown as StickerRow | null
    },

    async byIds(ids) {
      if (!ids.length) return []
      const res = await sb.from('stickers').select(STICKER_SELECT).in('id', ids.slice(0, MAX_IN))
      return unwrap(res, 'Load stickers') as unknown as StickerRow[]
    },

    async bySource(sourceId) {
      const res = await sb
        .from('stickers')
        .select(STICKER_SELECT)
        .eq('source_id', sourceId)
        .order('created_at', { ascending: false })
      return unwrap(res, 'Load stickers') as unknown as StickerRow[]
    },

    async update(id, patch) {
      const res = await sb.from('stickers').update(patch).eq('id', id).select(STICKER_SELECT).single()
      const row = unwrap(res, 'Save sticker') as unknown as StickerRow
      await refreshPackCounts(sb, [id]) // bumps the version of packs holding it, so phones re-sync metadata
      return row
    },

    async setStatus(ids, status) {
      if (!ids.length) return
      const res = await sb.from('stickers').update({ status }).in('id', ids).neq('status', 'taken_down')
      unwrap(res, 'Update stickers')
      await refreshPackCounts(sb, ids)
    },

    publicUrl(filePath) {
      return sb.storage.from('stickers').getPublicUrl(filePath).data.publicUrl
    },

    async countSince(column, since) {
      const res = await sb.from('stickers').select('id', { count: 'exact', head: true }).gte(column, since)
      return unwrapCount(res, 'Count stickers')
    },
  }
}

// ------------------------------------------------------------------ demo

export function stickerSearchText(s: Sticker): string {
  return [s.caption, s.description, s.tags.join(' '), s.emojis.join(' '), s.category].filter(Boolean).join(' ')
}

function sortStickers(rows: Sticker[], sort: StickerFilter['sort']): Sticker[] {
  const val = (s: Sticker): string | number | null =>
    sort === 'published' ? s.published_at : sort === 'quality' ? s.quality : sort === 'size' ? s.bytes : s.created_at
  return [...rows].sort((a, b) => {
    const av = val(a)
    const bv = val(b)
    if (av === bv) return b.created_at.localeCompare(a.created_at)
    if (av === null) return 1
    if (bv === null) return -1
    return av < bv ? 1 : -1
  })
}

export function demoStickers(db: DemoDb): StickersApi {
  return {
    async list(f, page) {
      await delay(db)
      const terms = searchTerms(f.q)
      const rows = sortStickers(
        db.stickers.filter(
          (s) =>
            (!f.status || s.status === f.status) &&
            (!f.category || s.category === f.category) &&
            (!f.rating || s.rating === f.rating) &&
            (!f.kind || s.kind === f.kind) &&
            !f.excludeIds?.includes(s.id) &&
            (!terms.length || matchesTerms(stickerSearchText(s), terms)),
        ),
        f.sort,
      )
      return { rows: rows.slice(page.offset, page.offset + page.limit).map((s) => demoStickerRow(db, s)), total: rows.length }
    },

    async get(id) {
      await delay(db)
      const s = db.stickers.find((x) => x.id === id)
      return s ? demoStickerRow(db, s) : null
    },

    async byIds(ids) {
      await delay(db)
      return db.stickers.filter((s) => ids.includes(s.id)).map((s) => demoStickerRow(db, s))
    },

    async bySource(sourceId) {
      await delay(db)
      return sortStickers(db.stickers.filter((s) => s.source_id === sourceId), 'created').map((s) => demoStickerRow(db, s))
    },

    async update(id, patch) {
      await delay(db)
      const s = db.stickers.find((x) => x.id === id)
      if (!s) throw demoError('Save sticker: not found', 'PGRST116')
      if (patch.emojis && patch.emojis.length > 3) {
        throw demoError('Save sticker: a database rule rejected it (stickers_emojis_check)', '23514')
      }
      Object.assign(s, structuredClone(patch), { updated_at: new Date().toISOString() })
      refreshPackCountsDemo(db, [id])
      return demoStickerRow(db, s)
    },

    async setStatus(ids, status) {
      await delay(db)
      const at = new Date().toISOString()
      for (const s of db.stickers) {
        if (!ids.includes(s.id) || s.status === 'taken_down') continue
        s.status = status
        s.updated_at = at
        if (status === 'published' && !s.published_at) s.published_at = at // mirrors sticker_search_text()
      }
      refreshPackCountsDemo(db, ids)
    },

    publicUrl(filePath) {
      return db.objects.get(`stickers/${filePath}`) ?? ''
    },

    async countSince(column, since) {
      await delay(db)
      return db.stickers.filter((s) => (s[column] ?? '') >= since).length
    },
  }
}
