// Pieces used by more than one table module.

import type { SupabaseClient } from '@supabase/supabase-js'
import { touchPackCounts, type DemoDb } from './demo/db'
import { SOURCE_FLAG_COLUMNS, STICKER_COLUMNS, type SourceFlags, type Sticker, type StickerRow } from './types'
import { unwrap } from './util'

/** Sticker columns plus the safety/provenance flags of its source (FK stickers.source_id). */
export const STICKER_SELECT = `${STICKER_COLUMNS.join(',')},source:source_images(${SOURCE_FLAG_COLUMNS.join(',')})`

/**
 * The schema's pack_items trigger recounts packs.sticker_count (published stickers only) but
 * runs only when pack_items rows change. After a sticker's status changes, re-touch one item
 * row per affected pack so the trigger recounts; discover_packs needs sticker_count >= 3.
 */
export async function refreshPackCounts(sb: SupabaseClient, stickerIds: string[]): Promise<void> {
  if (!stickerIds.length) return
  const res = await sb.from('pack_items').select('pack_id,sticker_id,position').in('sticker_id', stickerIds)
  const rows = unwrap(res, 'Load pack items') as { pack_id: string; sticker_id: string; position: number }[]
  const firstPerPack = new Map<string, (typeof rows)[number]>()
  for (const r of rows) if (!firstPerPack.has(r.pack_id)) firstPerPack.set(r.pack_id, r)
  await Promise.all(
    [...firstPerPack.values()].map(async (r) => {
      const upd = await sb
        .from('pack_items')
        .update({ position: r.position })
        .eq('pack_id', r.pack_id)
        .eq('sticker_id', r.sticker_id)
      unwrap(upd, 'Refresh pack count')
    }),
  )
}

export function refreshPackCountsDemo(db: DemoDb, stickerIds: string[]): void {
  const packIds = new Set(db.packItems.filter((i) => stickerIds.includes(i.sticker_id)).map((i) => i.pack_id))
  for (const id of packIds) touchPackCounts(db, id)
}

export function demoSourceFlags(db: DemoDb, sourceId: string | null): SourceFlags | null {
  const s = sourceId ? db.sources.find((x) => x.id === sourceId) : undefined
  if (!s) return null
  return {
    id: s.id,
    site: s.site,
    source_url: s.source_url,
    page_url: s.page_url,
    author: s.author,
    license: s.license,
    nsfw: s.nsfw,
    minors: s.minors,
    real_person: s.real_person,
    public_figure: s.public_figure,
  }
}

export function demoStickerRow(db: DemoDb, s: Sticker): StickerRow {
  return { ...structuredClone(s), source: demoSourceFlags(db, s.source_id) }
}
