// takedowns (fingerprints that must never be ingested or published again) + RPC take_down().

import type { SupabaseClient } from '@supabase/supabase-js'
import { clone, delay, demoError, demoUuid, type DemoDb } from './demo/db'
import { refreshPackCounts, refreshPackCountsDemo } from './shared'
import type { Takedown, TargetType, Uuid } from './types'
import { unwrap } from './util'

export interface TakedownsApi {
  /**
   * Calls public.take_down(): marks the sticker/pack taken_down, records sha256/phash/source_url
   * fingerprints, marks the source taken_down and resolves `reportId` as actioned.
   */
  takeDown(type: TargetType, id: Uuid, reason: string, reportId?: Uuid | null): Promise<void>
  recent(limit: number): Promise<Takedown[]>
}

export function supabaseTakedowns(sb: SupabaseClient): TakedownsApi {
  return {
    async takeDown(type, id, reason, reportId = null) {
      const res = await sb.rpc('take_down', { p_type: type, p_id: id, p_reason: reason, p_report: reportId })
      unwrap(res, 'Take down')
      if (type === 'sticker') await refreshPackCounts(sb, [id])
    },
    async recent(limit) {
      const res = await sb.from('takedowns').select('*').order('created_at', { ascending: false }).limit(limit)
      return unwrap(res, 'Load takedowns') as Takedown[]
    },
  }
}

/** Mirrors public.take_down() from the core migration. */
export function demoTakedowns(db: DemoDb): TakedownsApi {
  return {
    async takeDown(type, id, reason, reportId = null) {
      await delay(db)
      const at = new Date().toISOString()
      const fingerprint = (t: Omit<Takedown, 'id' | 'created_at' | 'reason' | 'report_id'>) =>
        db.takedowns.unshift({ id: demoUuid(), created_at: at, reason, report_id: reportId, ...t })
      if (type === 'sticker') {
        const s = db.stickers.find((x) => x.id === id)
        if (s) {
          s.status = 'taken_down'
          s.review_note = reason
          s.updated_at = at
          fingerprint({ sha256: s.sha256, phash: s.phash, source_url: null })
          const src = s.source_id ? db.sources.find((x) => x.id === s.source_id) : undefined
          if (src) {
            src.status = 'taken_down'
            src.updated_at = at
            fingerprint({ sha256: src.sha256, phash: null, source_url: src.source_url })
          }
          for (const p of db.packs) {
            if (db.packItems.some((i) => i.pack_id === p.id && i.sticker_id === id)) p.version += 1
          }
          refreshPackCountsDemo(db, [id])
        }
      } else if (type === 'pack') {
        const p = db.packs.find((x) => x.id === id)
        if (p) {
          p.status = 'taken_down'
          p.updated_at = at
        }
      } else {
        throw demoError('Take down: bad target type', 'P0001')
      }
      if (reportId) {
        const r = db.reports.find((x) => x.id === reportId)
        if (r) {
          r.status = 'actioned'
          r.resolved_at = at
        }
      }
    },
    async recent(limit) {
      await delay(db)
      return db.takedowns.slice(0, limit).map(clone)
    },
  }
}
