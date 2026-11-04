// Assembles the per-table modules into one Api, backed either by Supabase or by the demo store.

import type { SupabaseClient } from '@supabase/supabase-js'
import { demoAiCalls, supabaseAiCalls, type AiCallsApi } from './aiCalls'
import { demoAuth, supabaseAuth, type AuthApi } from './auth'
import type { DemoDb } from './demo/db'
import { demoPacks, supabasePacks, type PacksApi } from './packs'
import { demoProtectedPeople, supabaseProtectedPeople, type ProtectedPeopleApi } from './protectedPeople'
import { demoReports, supabaseReports, type ReportsApi } from './reports'
import { demoSettings, supabaseSettings, type SettingsApi } from './settings'
import { demoSources, supabaseSources, type SourcesApi } from './sources'
import { demoStickers, supabaseStickers, type StickersApi } from './stickers'
import { demoTakedowns, supabaseTakedowns, type TakedownsApi } from './takedowns'
import { demoTrends, supabaseTrends, type TrendsApi } from './trends'
import { demoWorkerRuns, supabaseWorkerRuns, type WorkerRunsApi } from './workerRuns'

export interface Api {
  mode: 'demo' | 'live'
  auth: AuthApi
  sources: SourcesApi
  stickers: StickersApi
  packs: PacksApi
  reports: ReportsApi
  takedowns: TakedownsApi
  trends: TrendsApi
  settings: SettingsApi
  protectedPeople: ProtectedPeopleApi
  aiCalls: AiCallsApi
  workerRuns: WorkerRunsApi
}

export function createSupabaseApi(sb: SupabaseClient): Api {
  return {
    mode: 'live',
    auth: supabaseAuth(sb),
    sources: supabaseSources(sb),
    stickers: supabaseStickers(sb),
    packs: supabasePacks(sb),
    reports: supabaseReports(sb),
    takedowns: supabaseTakedowns(sb),
    trends: supabaseTrends(sb),
    settings: supabaseSettings(sb),
    protectedPeople: supabaseProtectedPeople(sb),
    aiCalls: supabaseAiCalls(sb),
    workerRuns: supabaseWorkerRuns(sb),
  }
}

export function createDemoApi(db: DemoDb): Api {
  return {
    mode: 'demo',
    auth: demoAuth(db),
    sources: demoSources(db),
    stickers: demoStickers(db),
    packs: demoPacks(db),
    reports: demoReports(db),
    takedowns: demoTakedowns(db),
    trends: demoTrends(db),
    settings: demoSettings(db),
    protectedPeople: demoProtectedPeople(db),
    aiCalls: demoAiCalls(db),
    workerRuns: demoWorkerRuns(db),
  }
}
