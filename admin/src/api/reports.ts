// reports (user reports of stickers/packs).

import type { SupabaseClient } from '@supabase/supabase-js'
import { clone, delay, type DemoDb } from './demo/db'
import type { Page, PageReq, Report, ReportStatus, Uuid } from './types'
import { unwrap, unwrapCount } from './util'

export interface ReportsApi {
  /** Open reports oldest first (takedown SLA); resolved ones newest first. */
  list(status: ReportStatus, page: PageReq): Promise<Page<Report>>
  dismiss(ids: Uuid[]): Promise<void>
  /** Resolves every still-open report about one target. */
  resolveTarget(targetId: Uuid, status: Exclude<ReportStatus, 'open'>): Promise<void>
  countOpen(): Promise<number>
}

export function supabaseReports(sb: SupabaseClient): ReportsApi {
  return {
    async list(status, page) {
      const res = await sb
        .from('reports')
        .select('*', { count: 'exact' })
        .eq('status', status)
        .order(status === 'open' ? 'created_at' : 'resolved_at', { ascending: status === 'open', nullsFirst: false })
        .range(page.offset, page.offset + page.limit - 1)
      return { rows: unwrap(res, 'Load reports') as Report[], total: res.count ?? 0 }
    },

    async dismiss(ids) {
      if (!ids.length) return
      const res = await sb
        .from('reports')
        .update({ status: 'dismissed', resolved_at: new Date().toISOString() })
        .in('id', ids)
        .eq('status', 'open')
      unwrap(res, 'Dismiss reports')
    },

    async resolveTarget(targetId, status) {
      const res = await sb
        .from('reports')
        .update({ status, resolved_at: new Date().toISOString() })
        .eq('target_id', targetId)
        .eq('status', 'open')
      unwrap(res, 'Resolve reports')
    },

    async countOpen() {
      const res = await sb.from('reports').select('id', { count: 'exact', head: true }).eq('status', 'open')
      return unwrapCount(res, 'Count reports')
    },
  }
}

export function demoReports(db: DemoDb): ReportsApi {
  const resolve = (pred: (r: Report) => boolean, status: Exclude<ReportStatus, 'open'>) => {
    const at = new Date().toISOString()
    for (const r of db.reports) {
      if (r.status === 'open' && pred(r)) {
        r.status = status
        r.resolved_at = at
      }
    }
  }
  return {
    async list(status, page) {
      await delay(db)
      const rows = db.reports
        .filter((r) => r.status === status)
        .sort((a, b) =>
          status === 'open' ? a.created_at.localeCompare(b.created_at) : (b.resolved_at ?? '').localeCompare(a.resolved_at ?? ''),
        )
      return { rows: rows.slice(page.offset, page.offset + page.limit).map(clone), total: rows.length }
    },
    async dismiss(ids) {
      await delay(db)
      resolve((r) => ids.includes(r.id), 'dismissed')
    },
    async resolveTarget(targetId, status) {
      await delay(db)
      resolve((r) => r.target_id === targetId, status)
    },
    async countOpen() {
      return db.reports.filter((r) => r.status === 'open').length
    },
  }
}
