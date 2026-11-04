// trends (daily topics; owner topics are inserted with source = 'owner').

import type { SupabaseClient } from '@supabase/supabase-js'
import { clone, delay, demoError, demoUuid, type DemoDb } from './demo/db'
import type { Trend, TrendInput, Uuid } from './types'
import { unwrap } from './util'

export interface TrendsApi {
  list(day: string): Promise<Trend[]>
  addOwnerTopic(input: TrendInput): Promise<Trend>
  setActive(id: Uuid, active: boolean): Promise<void>
}

function cleanInput(input: TrendInput): TrendInput {
  return {
    ...input,
    title: input.title.trim(),
    summary: input.summary?.trim() || null,
    keywords: [...new Set(input.keywords.map((k) => k.trim().toLowerCase()).filter(Boolean))],
    category: input.category || null,
  }
}

export function supabaseTrends(sb: SupabaseClient): TrendsApi {
  return {
    async list(day) {
      const res = await sb
        .from('trends')
        .select('*')
        .eq('day', day)
        .order('active', { ascending: false })
        .order('score', { ascending: false })
      return unwrap(res, 'Load trends') as Trend[]
    },
    async addOwnerTopic(input) {
      const res = await sb
        .from('trends')
        .insert({ ...cleanInput(input), source: 'owner' })
        .select('*')
        .single()
      return unwrap(res, 'Add topic') as Trend
    },
    async setActive(id, active) {
      unwrap(await sb.from('trends').update({ active }).eq('id', id), 'Update topic')
    },
  }
}

export function demoTrends(db: DemoDb): TrendsApi {
  return {
    async list(day) {
      await delay(db)
      return db.trends
        .filter((t) => t.day === day)
        .sort((a, b) => Number(b.active) - Number(a.active) || b.score - a.score)
        .map(clone)
    },
    async addOwnerTopic(input) {
      await delay(db)
      const t = cleanInput(input)
      if (!t.title) throw demoError('Add topic: title is required', '23502')
      if (db.trends.some((x) => x.day === t.day && x.title === t.title)) {
        throw demoError(`Add topic: already exists (a topic called "${t.title}" exists for ${t.day})`, '23505')
      }
      const row: Trend = { ...t, id: demoUuid(), source: 'owner', source_urls: [], active: true, created_at: new Date().toISOString() }
      db.trends.push(row)
      return clone(row)
    },
    async setActive(id, active) {
      await delay(db)
      const t = db.trends.find((x) => x.id === id)
      if (t) t.active = active
    },
  }
}
