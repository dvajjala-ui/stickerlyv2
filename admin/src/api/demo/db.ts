// In-memory tables for demo mode. The demo implementations in each api/<table>.ts module read
// and write these arrays and mimic the schema's triggers and RPCs.

import type {
  Pack,
  ProtectedPerson,
  Report,
  SettingKey,
  SettingValues,
  SourceImage,
  Sticker,
  Takedown,
  Trend,
  WorkerRun,
} from '../types'
import { ApiError } from '../util'

export interface DemoPackItem {
  pack_id: string
  sticker_id: string
  position: number
  added_at: string
}

export interface DemoAiCall {
  id: number
  at: string
  purpose: string
  ok: boolean
}

export interface DemoDb {
  latencyMs: number
  admins: { user_id: string; email: string }[]
  sources: SourceImage[]
  stickers: Sticker[]
  packs: Pack[]
  packItems: DemoPackItem[]
  reports: Report[]
  takedowns: Takedown[]
  trends: Trend[]
  settings: { [K in SettingKey]: { value: SettingValues[K]; updated_at: string } }
  protectedPeople: ProtectedPerson[]
  aiCalls: DemoAiCall[]
  workerRuns: WorkerRun[]
  /** Storage objects: `<bucket>/<path>` → displayable URL (data: or blob:). */
  objects: Map<string, string>
}

export function emptyDemoDb(latencyMs = 0): DemoDb {
  const at = new Date().toISOString()
  return {
    latencyMs,
    admins: [],
    sources: [],
    stickers: [],
    packs: [],
    packItems: [],
    reports: [],
    takedowns: [],
    trends: [],
    settings: {
      publish_policy: {
        value: { auto_publish: true, min_quality: 6, adult_lane: true, celebrities: 'publish', require_review_sites: [] },
        updated_at: at,
      },
      budgets: {
        value: { vision_per_day: 60, text_per_day: 400, image_gen_per_day: 0, max_new_stickers_per_day: 120 },
        updated_at: at,
      },
      categories: { value: { enabled: ['reactions', 'funny'] }, updated_at: at },
      quality_gate: { value: { enabled: true, min_score: 60, vision_judge: false, judge_band: 20 }, updated_at: at },
    },
    protectedPeople: [],
    aiCalls: [],
    workerRuns: [],
    objects: new Map(),
  }
}

export function delay(db: DemoDb): Promise<void> {
  return db.latencyMs > 0 ? new Promise((r) => setTimeout(r, db.latencyMs)) : Promise.resolve()
}

/** Deep copy so callers can't mutate the store through returned rows. */
export function clone<T>(value: T): T {
  return structuredClone(value)
}

export function demoUuid(): string {
  return crypto.randomUUID()
}

export function demoError(message: string, code: string): ApiError {
  return new ApiError(message, code)
}

/** Mirrors the pack_items trigger: recount published stickers and bump the version. */
export function touchPackCounts(db: DemoDb, packId: string, bumps = 1): void {
  const pack = db.packs.find((p) => p.id === packId)
  if (!pack) return
  const ids = db.packItems.filter((i) => i.pack_id === packId).map((i) => i.sticker_id)
  pack.sticker_count = db.stickers.filter((s) => ids.includes(s.id) && s.status === 'published').length
  pack.version += bumps
}
