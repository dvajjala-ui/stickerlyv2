// ai_calls (usage ledger written by the worker; read-only for admins).

import type { SupabaseClient } from '@supabase/supabase-js'
import { delay, type DemoDb } from './demo/db'
import { IMAGE_PURPOSES, VISION_PURPOSES, type AiUsage, type Iso } from './types'
import { unwrapCount } from './util'

export interface AiCallsApi {
  /** Calls since `since`, bucketed like the daily budgets (vision / text / image generation). */
  usageSince(since: Iso): Promise<AiUsage>
}

const NON_TEXT = [...VISION_PURPOSES, ...IMAGE_PURPOSES]

export function supabaseAiCalls(sb: SupabaseClient): AiCallsApi {
  // Count queries (head: true) so PostgREST's max-rows cap can't truncate the numbers.
  const base = () => sb.from('ai_calls').select('id', { count: 'exact', head: true })
  const count = async (build: (q: ReturnType<typeof base>) => ReturnType<typeof base>) =>
    unwrapCount(await build(base()), 'Count AI calls')
  return {
    async usageSince(since) {
      const [vision, image, text, failed, total] = await Promise.all([
        count((q) => q.gte('at', since).in('purpose', [...VISION_PURPOSES])),
        count((q) => q.gte('at', since).in('purpose', [...IMAGE_PURPOSES])),
        count((q) => q.gte('at', since).not('purpose', 'in', `(${NON_TEXT.join(',')})`)),
        count((q) => q.gte('at', since).eq('ok', false)),
        count((q) => q.gte('at', since)),
      ])
      return { vision, image, text, failed, total }
    },
  }
}

export function demoAiCalls(db: DemoDb): AiCallsApi {
  return {
    async usageSince(since) {
      await delay(db)
      const rows = db.aiCalls.filter((c) => c.at >= since)
      const isVision = (p: string) => (VISION_PURPOSES as readonly string[]).includes(p)
      const isImage = (p: string) => (IMAGE_PURPOSES as readonly string[]).includes(p)
      return {
        vision: rows.filter((c) => isVision(c.purpose)).length,
        image: rows.filter((c) => isImage(c.purpose)).length,
        text: rows.filter((c) => !isVision(c.purpose) && !isImage(c.purpose)).length,
        failed: rows.filter((c) => !c.ok).length,
        total: rows.length,
      }
    },
  }
}
