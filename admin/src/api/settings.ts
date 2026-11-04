// settings (jsonb key/value: publish_policy, budgets, categories).

import type { SupabaseClient } from '@supabase/supabase-js'
import { clone, delay, type DemoDb } from './demo/db'
import { DEFAULT_SETTINGS, type Iso, type SettingKey, type SettingValues, type SettingsBundle } from './types'
import { unwrap } from './util'

export interface SettingsApi {
  load(): Promise<SettingsBundle>
  save<K extends SettingKey>(key: K, value: SettingValues[K]): Promise<Iso>
}

/** Merges stored values over the migration defaults so a missing field never crashes the UI. */
export function mergeSettings(rows: { key: string; value: unknown; updated_at: Iso }[]): SettingsBundle {
  const out: SettingsBundle = { ...clone(DEFAULT_SETTINGS), updated_at: {} }
  for (const r of rows) {
    if (!(r.key in DEFAULT_SETTINGS) || !r.value || typeof r.value !== 'object') continue
    const key = r.key as SettingKey
    Object.assign(out[key], r.value)
    out.updated_at[key] = r.updated_at
  }
  return out
}

export function supabaseSettings(sb: SupabaseClient): SettingsApi {
  return {
    async load() {
      const res = await sb.from('settings').select('key,value,updated_at')
      return mergeSettings(unwrap(res, 'Load settings') as { key: string; value: unknown; updated_at: Iso }[])
    },
    async save(key, value) {
      const updated_at = new Date().toISOString()
      const res = await sb.from('settings').upsert({ key, value, updated_at }, { onConflict: 'key' })
      unwrap(res, 'Save settings')
      return updated_at
    },
  }
}

export function demoSettings(db: DemoDb): SettingsApi {
  return {
    async load() {
      await delay(db)
      return mergeSettings(Object.entries(db.settings).map(([key, v]) => ({ key, value: v.value, updated_at: v.updated_at })))
    },
    async save(key, value) {
      await delay(db)
      const updated_at = new Date().toISOString()
      const slots = db.settings as Record<SettingKey, { value: unknown; updated_at: string }>
      slots[key] = { value: clone(value), updated_at }
      return updated_at
    },
  }
}
